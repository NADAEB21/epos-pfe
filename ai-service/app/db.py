"""Accès LECTURE SEULE aux bases du cœur, via le rôle `ai_reader` (ADR-0029 D2).

Deux DSN, deux bases (Postgres ne joint pas entre bases — les croisements se
font en Python) :
- scoring_db : notations verrouillées, snapshots, résolution examen → matière ;
- exam_db    : critères avec `valeur_max`/`libelle` (absents du snapshot scoring).

Le rôle est SELECT-only avec `default_transaction_read_only=on` et
`statement_timeout` côté serveur : même un bug ici ne peut pas écrire.
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
_CONNECT_TIMEOUT = int(os.environ.get("AI_DB_CONNECT_TIMEOUT_S", "3"))


def resolve_matiere(examen_id: int) -> int | None:
    """examen → matière depuis la vue scoring `v_ai_exam_matiere` (snapshot #274)."""
    with psycopg.connect(SCORING_DSN, connect_timeout=_CONNECT_TIMEOUT) as conn:
        row = conn.execute(
            "SELECT matiere_id FROM v_ai_exam_matiere WHERE examen_id = %s",
            (examen_id,),
        ).fetchone()
    return row[0] if row else None
