"""Le journal ai_db (#362) contre une connexion factice : le DDL joué une
fois par processus et par instruction, l'insertion « déjà montré = jamais
réécrit », la décision posée une seule fois, la ligne rendue en JSON-friendly.
"""

from contextlib import contextmanager
from datetime import datetime, timezone

import pytest

from app import journal


class FauxCurseur:
    def __init__(self, resultat):
        self._r = resultat

    def fetchone(self):
        return self._r if not isinstance(self._r, list) else (self._r[0] if self._r else None)

    def fetchall(self):
        return self._r if isinstance(self._r, list) else ([self._r] if self._r else [])


class FauxConn:
    def __init__(self, resultats=None):
        self.sql: list[str] = []
        self.params: list = []
        self.commits = 0
        self._resultats = list(resultats or [])

    def execute(self, sql, params=None):
        self.sql.append(" ".join(sql.split()))
        self.params.append(params)
        return FauxCurseur(self._resultats.pop(0) if self._resultats else None)

    def commit(self):
        self.commits += 1


@pytest.fixture
def conn(monkeypatch):
    c = FauxConn()

    @contextmanager
    def connexion():
        yield c

    monkeypatch.setattr("app.journal._connexion", connexion)
    monkeypatch.setattr("app.journal._schema_pret", False)
    return c


def test_ddl_une_instruction_par_execute_et_une_fois(conn):
    journal._assurer_schema(conn)
    journal._assurer_schema(conn)
    ddl = [s for s in conn.sql if s.startswith("CREATE")]
    assert len(ddl) == 2
    assert ddl[0].startswith("CREATE TABLE IF NOT EXISTS propositions_journal")
    assert ddl[1].startswith("CREATE INDEX IF NOT EXISTS idx_propositions_journal_examen")
    assert "CHECK (decision IN ('ACCEPTER', 'REFUSER'))" in ddl[0]
    assert conn.commits == 1


def test_enregistrer_insert_on_conflict_do_nothing(conn):
    journal.enregistrer([{
        "proposition_id": "ab" * 16, "examen_id": 80, "entrees_hash": "h", "moteur_version": "m",
        "bareme_version_base": None, "operation": {"type": "EXCLURE_CRITERE"},
        "declencheur": [{"code": "DIFFICULTE"}], "effet_projete": {"avant": {}},
    }])
    inserts = [s for s in conn.sql if s.startswith("INSERT")]
    assert len(inserts) == 1
    assert "ON CONFLICT (proposition_id) DO NOTHING" in inserts[0]
    assert conn.params[-1][0] == "ab" * 16 and conn.params[-1][5] == '{"type": "EXCLURE_CRITERE"}'
    assert conn.commits >= 1


def test_enregistrer_vide_ne_touche_pas_la_base(conn):
    journal.enregistrer([])
    assert conn.sql == []


def _ligne_brute():
    return ("ab" * 16, 80, "h", "m", None, {"type": "EXCLURE_CRITERE"}, [], {"avant": {}},
            datetime(2026, 9, 1, 10, 0, tzinfo=timezone.utc), None, None, None, None, None)


def test_lire_rend_une_ligne_json_friendly(monkeypatch):
    c = FauxConn(resultats=[None, None, _ligne_brute()])  # 2 DDL puis le SELECT

    @contextmanager
    def connexion():
        yield c

    monkeypatch.setattr("app.journal._connexion", connexion)
    monkeypatch.setattr("app.journal._schema_pret", False)
    ligne = journal.lire("ab" * 16)
    assert ligne["proposition_id"] == "ab" * 16 and ligne["examen_id"] == 80
    assert ligne["proposee_a"] == "2026-09-01T10:00:00+00:00"
    assert ligne["decision"] is None and ligne["decide_a"] is None


def test_decider_none_si_aucune_ligne_mise_a_jour(conn):
    assert journal.decider("ab" * 16, "REFUSER", "m", 5, None) is None
    update = [s for s in conn.sql if s.startswith("UPDATE")][0]
    assert "WHERE proposition_id = %s AND decision IS NULL" in update
    assert "RETURNING" in update


def test_decider_rend_la_ligne_decidee(monkeypatch):
    decidee = _ligne_brute()[:9] + ("ACCEPTER", "motif", 5, datetime(2026, 9, 1, 10, 5, tzinfo=timezone.utc), 1)
    c = FauxConn(resultats=[None, None, decidee])

    @contextmanager
    def connexion():
        yield c

    monkeypatch.setattr("app.journal._connexion", connexion)
    monkeypatch.setattr("app.journal._schema_pret", False)
    ligne = journal.decider("ab" * 16, "ACCEPTER", "motif", 5, 1)
    assert ligne["decision"] == "ACCEPTER" and ligne["decide_par"] == 5
    assert ligne["bareme_version_resultat"] == 1 and ligne["decide_a"] == "2026-09-01T10:05:00+00:00"
    assert c.params[-1] == ("ACCEPTER", "motif", 5, 1, "ab" * 16)
