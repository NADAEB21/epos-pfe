"""Accès aux bases du module IA — deux rôles, deux périmètres (ADR-0029 D2/D3).

LECTURE (rôle `ai_reader`, SELECT-only, `default_transaction_read_only=on`,
`statement_timeout` côté serveur — même un bug ici ne peut pas écrire) :
- scoring_db : notations verrouillées, totaux, résolution examen → matière ;
- exam_db    : critères avec `valeur_max`/`libelle` + statut d'examen (V12).

ÉCRITURE (rôle `ai_writer`, #359) : UNIQUEMENT ai_db (cache + journal) — le
rôle n'a aucun droit sur scoring_db/exam_db, c'est la preuve de robustesse D2.
La connexion ai_db vit dans `app.cache`, pas ici : ce module reste le plan de
lecture du cœur.

Postgres ne joint pas entre bases — les croisements se font en Python.
"""

import os

import psycopg

SCORING_DSN = os.environ.get(
    "AI_SCORING_DSN",
    "host=localhost port=5432 dbname=scoring_db user=ai_reader",
)
EXAM_DSN = os.environ.get(
    "AI_EXAM_DSN",
    "host=localhost port=5432 dbname=exam_db user=ai_reader",
)
AI_DSN = os.environ.get(
    "AI_DB_DSN",
    "host=localhost port=5432 dbname=ai_db user=ai_writer",
)
_CONNECT_TIMEOUT = int(os.environ.get("AI_DB_CONNECT_TIMEOUT_S", "3"))

# Statuts d'examen sur lesquels le calcul est permis (ADR-0029 D2 : « le calcul
# ne porte que sur des examens CLOS »). État PERSISTÉ écrit par un acte humain
# (Terminer, gated par toutesVaguesTerminees) — jamais une dérivation d'horloge
# (ADR-0014) : la garde hérite de cette propriété.
STATUTS_CLOS = frozenset({"TERMINE", "ARCHIVE"})


def resolve_matiere(examen_id: int) -> int | None:
    """examen → matière depuis la vue scoring `v_ai_exam_matiere` (snapshot #274)."""
    with psycopg.connect(SCORING_DSN, connect_timeout=_CONNECT_TIMEOUT) as conn:
        row = conn.execute(
            "SELECT matiere_id FROM v_ai_exam_matiere WHERE examen_id = %s",
            (examen_id,),
        ).fetchone()
    return row[0] if row else None


def statut_examen(examen_id: int) -> str | None:
    """Statut PERSISTÉ de l'examen, depuis la vue exam `v_ai_examens` (V12, #359)."""
    with psycopg.connect(EXAM_DSN, connect_timeout=_CONNECT_TIMEOUT) as conn:
        row = conn.execute(
            "SELECT statut FROM v_ai_examens WHERE examen_id = %s",
            (examen_id,),
        ).fetchone()
    return row[0] if row else None


def bareme_courant(examen_id: int) -> list[tuple]:
    """Lignes (version, op_type, cible_item_id, cible_station_id, nouvelle_echelle)
    de la version COURANTE du barème de délibération (vue scoring V26, #362).

    Liste vide = aucun barème. Une version VIDE (retour à l'origine, ADR-0030
    D3) rend UNE ligne dont op_type est NULL (LEFT JOIN de la vue) — « pas de
    barème » ≠ « barème vide », la distinction remonte telle quelle.
    """
    with psycopg.connect(SCORING_DSN, connect_timeout=_CONNECT_TIMEOUT) as conn:
        return conn.execute(
            """
            SELECT version, op_type, cible_item_id, cible_station_id, nouvelle_echelle
            FROM v_ai_bareme_deliberation
            WHERE examen_id = %s
              AND version = (SELECT MAX(version) FROM v_ai_bareme_deliberation
                             WHERE examen_id = %s)
            ORDER BY cible_station_id NULLS LAST, cible_item_id NULLS LAST
            """,
            (examen_id, examen_id),
        ).fetchall()


def grilles_snapshot(examen_id: int) -> list[tuple]:
    """Lignes (station_id, grille_id, note_max, items_json) du snapshot de grille
    V9 (vue scoring V26, #362) — le barème qui a RÉELLEMENT servi à noter.

    Se matérialise PAR STATION au fil de la notation (leçon S48) : la
    couverture peut être partielle, l'appelant la mesure et la DIT.
    """
    with psycopg.connect(SCORING_DSN, connect_timeout=_CONNECT_TIMEOUT) as conn:
        return conn.execute(
            """
            SELECT station_id, grille_id, note_max, items_json
            FROM v_ai_grille_snapshot
            WHERE examen_id = %s
            ORDER BY station_id
            """,
            (examen_id,),
        ).fetchall()


def nb_notations_verrouillees(examen_id: int) -> int:
    """Total de référence des notations verrouillées (vue scoring V23, #359).

    Sert à compter l'angle mort structurel : une notation verrouillée SANS
    AUCUN item est invisible de `v_ai_notations_verrouillees` (jointure
    interne) — l'écart entre ce total et les notations chargées devient
    l'exclusion « sans_aucun_item », dite au lieu de disparaître.
    """
    with psycopg.connect(SCORING_DSN, connect_timeout=_CONNECT_TIMEOUT) as conn:
        row = conn.execute(
            "SELECT nb_verrouillees FROM v_ai_notations_totaux WHERE examen_id = %s",
            (examen_id,),
        ).fetchone()
    return int(row[0]) if row else 0


# ── BI (#365 / N10) : les sessions d'une matière, de la faculté ──────────────

def examens_par_matiere(matiere_id: int) -> list[tuple]:
    """Lignes (examen_id, statut, nom, date_examen) des examens dont la matière
    VIVANTE (exam_db, vue V12) est ``matiere_id`` — tous statuts, dans l'ordre
    des dates. Ce n'est PAS la vérité de périmètre : ``app.bi`` re-vérifie
    chaque examen contre le snapshot scoring (``resolve_matiere``)."""
    with psycopg.connect(EXAM_DSN, connect_timeout=_CONNECT_TIMEOUT) as conn:
        return conn.execute(
            """
            SELECT examen_id, statut, nom, date_examen
            FROM v_ai_examens
            WHERE matiere_id = %s
            ORDER BY date_examen, examen_id
            """,
            (matiere_id,),
        ).fetchall()


def examens_clos_toutes_matieres() -> list[tuple]:
    """Lignes (examen_id, matiere_id, statut, nom, date_examen) de TOUS les
    examens clos (vue V12) — la synthèse facultaire regroupe ensuite par
    matière SNAPSHOT, jamais par cette matière vivante."""
    with psycopg.connect(EXAM_DSN, connect_timeout=_CONNECT_TIMEOUT) as conn:
        return conn.execute(
            """
            SELECT examen_id, matiere_id, statut, nom, date_examen
            FROM v_ai_examens
            WHERE statut IN ('TERMINE', 'ARCHIVE')
            ORDER BY date_examen, examen_id
            """,
        ).fetchall()
