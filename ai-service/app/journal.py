"""Le journal des propositions dans ai_db (#362, ADR-0029 D3) — rôle ``ai_writer``.

« Une suggestion irreproduisible devant le jury » est le risque que ce journal
pare : chaque proposition affichée y vit avec ses cinq familles (entrées
HASHÉES, indices déclencheurs, opération, effet projeté, décision). Un cache
est reconstructible ; ce journal est une TRACE D'AUDIT — il voyage avec le
drill de sauvegarde W9 (``backup-epos.ps1`` inclut ai_db).

Une ligne par proposition, clé ``proposition_id`` DÉTERMINISTE (sha256 de
examen + empreinte des entrées + version moteur + version de barème de base +
opération canonique) : un re-GET ne duplique jamais, une donnée qui bouge
(réajustement) produit une NOUVELLE proposition et laisse l'ancienne telle
qu'elle a été montrée. La décision (ACCEPTER / REFUSER, motif, auteur) s'écrit
par UPDATE sur la ligne, une seule fois — le refus est journalisé comme
l'acceptation (ADR-0030 D1 : « refus compris »).

Le barème lui-même n'est PAS ici : il vit dans scoring, écrit par le
responsable (D1). ``bareme_version_resultat`` est ce que le client rapporte
après son POST scoring ; la réalité (la version courante) se lit par la vue
V26 — les deux sont servies, jamais l'une déduite de l'autre.

Même convention que ``app.cache`` : pas de Flyway pour ai_db, DDL idempotent
posé au premier besoin, un ``execute`` par instruction ; échec BRUYANT (→ 503).
Le journal est une ÉCRITURE STRICTE (ADR-0015) : une proposition qui ne peut
pas être tracée n'est pas servie.
"""

import json

import psycopg

from app import db

_DDL_TABLE = """
    CREATE TABLE IF NOT EXISTS propositions_journal (
        proposition_id           TEXT        PRIMARY KEY,
        examen_id                BIGINT      NOT NULL,
        entrees_hash             TEXT        NOT NULL,
        moteur_version           TEXT        NOT NULL,
        bareme_version_base      INTEGER,
        operation                JSONB       NOT NULL,
        declencheur              JSONB       NOT NULL,
        effet_projete            JSONB,
        proposee_a               TIMESTAMPTZ NOT NULL DEFAULT now(),
        decision                 TEXT        CHECK (decision IN ('ACCEPTER', 'REFUSER')),
        motif                    TEXT,
        decide_par               BIGINT,
        decide_a                 TIMESTAMPTZ,
        bareme_version_resultat  INTEGER
    )
"""
_DDL_INDEX = """
    CREATE INDEX IF NOT EXISTS idx_propositions_journal_examen
        ON propositions_journal (examen_id)
"""

_COLONNES = (
    "proposition_id, examen_id, entrees_hash, moteur_version, bareme_version_base, "
    "operation, declencheur, effet_projete, proposee_a, decision, motif, decide_par, "
    "decide_a, bareme_version_resultat"
)

_schema_pret = False


def _connexion():
    return psycopg.connect(db.AI_DSN, connect_timeout=db._CONNECT_TIMEOUT)


def _assurer_schema(conn) -> None:
    """DDL idempotent — joué une fois par vie de processus, jamais par requête."""
    global _schema_pret
    if _schema_pret:
        return
    conn.execute(_DDL_TABLE)
    conn.execute(_DDL_INDEX)
    conn.commit()
    _schema_pret = True


def _ligne(row: tuple) -> dict:
    (pid, examen_id, entrees_hash, moteur_version, version_base, operation, declencheur,
     effet, proposee_a, decision, motif, decide_par, decide_a, version_resultat) = row
    return {
        "proposition_id": pid,
        "examen_id": examen_id,
        "entrees_hash": entrees_hash,
        "moteur_version": moteur_version,
        "bareme_version_base": version_base,
        "operation": operation,
        "declencheur": declencheur,
        "effet_projete": effet,
        "proposee_a": proposee_a.isoformat() if proposee_a is not None else None,
        "decision": decision,
        "motif": motif,
        "decide_par": decide_par,
        "decide_a": decide_a.isoformat() if decide_a is not None else None,
        "bareme_version_resultat": version_resultat,
    }


def enregistrer(propositions: list[dict]) -> None:
    """Insère les propositions ABSENTES (``ON CONFLICT DO NOTHING``) — une
    ligne déjà montrée n'est jamais réécrite : ce qui a été affiché reste ce
    qui a été affiché."""
    if not propositions:
        return
    with _connexion() as conn:
        _assurer_schema(conn)
        for p in propositions:
            conn.execute(
                """
                INSERT INTO propositions_journal
                    (proposition_id, examen_id, entrees_hash, moteur_version,
                     bareme_version_base, operation, declencheur, effet_projete)
                VALUES (%s, %s, %s, %s, %s, %s::jsonb, %s::jsonb, %s::jsonb)
                ON CONFLICT (proposition_id) DO NOTHING
                """,
                (
                    p["proposition_id"], p["examen_id"], p["entrees_hash"],
                    p["moteur_version"], p["bareme_version_base"],
                    json.dumps(p["operation"], ensure_ascii=False),
                    json.dumps(p["declencheur"], ensure_ascii=False),
                    json.dumps(p["effet_projete"], ensure_ascii=False),
                ),
            )
        conn.commit()


def lire_examen(examen_id: int) -> dict[str, dict]:
    """Toutes les lignes d'un examen, par ``proposition_id``."""
    with _connexion() as conn:
        _assurer_schema(conn)
        rows = conn.execute(
            f"SELECT {_COLONNES} FROM propositions_journal WHERE examen_id = %s "
            "ORDER BY proposee_a, proposition_id",
            (examen_id,),
        ).fetchall()
    return {r[0]: _ligne(r) for r in rows}


def lire(proposition_id: str) -> dict | None:
    with _connexion() as conn:
        _assurer_schema(conn)
        row = conn.execute(
            f"SELECT {_COLONNES} FROM propositions_journal WHERE proposition_id = %s",
            (proposition_id,),
        ).fetchone()
    return _ligne(row) if row else None


def decider(
    proposition_id: str,
    decision: str,
    motif: str,
    decide_par: int,
    bareme_version_resultat: int | None,
) -> dict | None:
    """Pose la décision — UNE fois. ``None`` si aucune ligne n'a été mise à
    jour (proposition inconnue OU déjà décidée : l'appelant distingue par
    ``lire``). Jamais d'écrasement d'une décision existante."""
    with _connexion() as conn:
        _assurer_schema(conn)
        row = conn.execute(
            f"""
            UPDATE propositions_journal
               SET decision = %s, motif = %s, decide_par = %s, decide_a = now(),
                   bareme_version_resultat = %s
             WHERE proposition_id = %s AND decision IS NULL
            RETURNING {_COLONNES}
            """,
            (decision, motif, decide_par, bareme_version_resultat, proposition_id),
        ).fetchone()
        conn.commit()
    return _ligne(row) if row else None
