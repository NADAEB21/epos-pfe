"""Le cache des indices dans ai_db (#359, ADR-0029 D3) — rôle `ai_writer`.

« Jamais de re-calcul par requête sur le chemin chaud » : les indices d'un
examen clos se calculent UNE fois par état des entrées, puis se servent depuis
ici. La clé est ``(examen_id, entrees_hash)`` (voir ``app.stats.hash``) — un
réajustement change le hash, donc force un recalcul ; aucun TTL, aucune
horloge.

Pas de Flyway pour ai_db : le schéma est posé ICI, en DDL idempotent, au
premier besoin (décision actée dans init2-ai.sh — la base doit exister avant,
le script l'assure). Les tables créées appartiennent à ``ai_writer``, qui n'a
AUCUN droit hors ai_db (preuve de robustesse D2).

Une ligne par (examen, empreinte) ; ``payload`` porte les DEUX vues du même
calcul (indices + evaluateurs) — un seul chargement, une seule empreinte, une
seule ligne. Écriture en UPSERT (update-in-place — le piège 23505
delete+insert du dépôt ne s'applique pas ici, mais la règle reste).

Échec bruyant : toute erreur remonte à l'appelant (→ 503, ADR-0029 D7). Un
cache injoignable ne « dégrade » pas en recalcul silencieux : la garantie de
reproductibilité (tout ce qui a été affiché existe hashé ici) tient parce que
ce chemin est obligatoire.
"""

import json

import psycopg

from app import db

_DDL = """
    CREATE TABLE IF NOT EXISTS indices_cache (
        examen_id     BIGINT      NOT NULL,
        entrees_hash  TEXT        NOT NULL,
        moteur_version TEXT       NOT NULL,
        payload       JSONB       NOT NULL,
        calcule_a     TIMESTAMPTZ NOT NULL DEFAULT now(),
        PRIMARY KEY (examen_id, entrees_hash)
    )
"""

_schema_pret = False


def _connexion():
    return psycopg.connect(db.AI_DSN, connect_timeout=db._CONNECT_TIMEOUT)


def _assurer_schema(conn) -> None:
    """DDL idempotent — joué une fois par vie de processus, jamais par requête."""
    global _schema_pret
    if _schema_pret:
        return
    conn.execute(_DDL)
    conn.commit()
    _schema_pret = True


def lire(examen_id: int, entrees_hash: str) -> dict | None:
    """Le payload caché pour cet état des entrées, ou None (cache miss)."""
    with _connexion() as conn:
        _assurer_schema(conn)
        row = conn.execute(
            "SELECT payload FROM indices_cache WHERE examen_id = %s AND entrees_hash = %s",
            (examen_id, entrees_hash),
        ).fetchone()
    return row[0] if row else None


def ecrire(examen_id: int, entrees_hash: str, moteur_version: str, payload: dict) -> None:
    """UPSERT du payload calculé — la ligne existante est mise à jour en place."""
    with _connexion() as conn:
        _assurer_schema(conn)
        conn.execute(
            """
            INSERT INTO indices_cache (examen_id, entrees_hash, moteur_version, payload)
            VALUES (%s, %s, %s, %s::jsonb)
            ON CONFLICT (examen_id, entrees_hash)
            DO UPDATE SET moteur_version = EXCLUDED.moteur_version,
                          payload        = EXCLUDED.payload,
                          calcule_a      = now()
            """,
            (examen_id, entrees_hash, moteur_version, json.dumps(payload, ensure_ascii=False)),
        )
        conn.commit()
