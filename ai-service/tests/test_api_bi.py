"""Les routes BI (#365 / N10) à travers HTTP : ``/ai/matieres/{id}/tendances``
et ``/ai/faculte/synthese``.

Verrouillé : 401 → 403 nominatif (jamais 404 sur une matière), l'évaluateur
sans accès, la synthèse réservée au SUPER_ADMIN, le 200 vide DIT, le 503
bruyant, l'enveloppe ADR-0004 — et, sur la synthèse, l'absence de tout
``etudiant_id`` dans le corps servi (ADR-0021 D5).
"""

import pytest

from conftest import (
    ADMIN, EVAL, RESP_M1, RESP_M2,
    EXAMEN_ARCHIVE, EXAMEN_CLOS, EXAMEN_EN_COURS, EXAMEN_HORS_MATIERE,
)


@pytest.fixture
def client_bi(client, monkeypatch):
    """Le client de conftest + les deux lectures de sessions : matière 1 =
    77 (TERMINE) · 55 (ARCHIVE) · 42 (EN_COURS) ; matière 4 = 63 (TERMINE)."""
    sessions = {
        1: [(EXAMEN_CLOS, "TERMINE", "Session juin", "2026-06-10"),
            (EXAMEN_ARCHIVE, "ARCHIVE", "Session janvier", "2026-01-15"),
            (EXAMEN_EN_COURS, "EN_COURS", "Session en cours", "2026-09-01")],
        4: [(EXAMEN_HORS_MATIERE, "TERMINE", "Autre matière", "2026-05-01")],
    }
    monkeypatch.setattr("app.db.examens_par_matiere", lambda mid: sessions.get(mid, []))
    monkeypatch.setattr("app.db.examens_clos_toutes_matieres", lambda: [
        (eid, mid, st, nom, d)
        for mid, lignes in sessions.items()
        for eid, st, nom, d in lignes if st in ("TERMINE", "ARCHIVE")
    ])
    return client


# ── /tendances : périmètre ───────────────────────────────────────────────────

def test_tendances_sans_identite_401(client_bi):
    r = client_bi.get("/ai/matieres/1/tendances")
    assert r.status_code == 401 and r.json()["success"] is False


def test_tendances_hors_matiere_403_nominatif(client_bi):
    r = client_bi.get("/ai/matieres/4/tendances", headers=RESP_M1)
    assert r.status_code == 403
    assert "matière 4" in r.json()["message"]


def test_tendances_evaluateur_403(client_bi):
    assert client_bi.get("/ai/matieres/1/tendances", headers=EVAL).status_code == 403


def test_tendances_matiere_inconnue_hors_perimetre_403_pas_404(client_bi):
    """On ne révèle pas si la matière existe : un responsable hors périmètre
    reçoit le même 403 qu'ailleurs."""
    assert client_bi.get("/ai/matieres/999/tendances", headers=RESP_M2).status_code == 403


def test_tendances_200_sessions_closes_ordonnees(client_bi):
    r = client_bi.get("/ai/matieres/1/tendances", headers=RESP_M1)
    assert r.status_code == 200
    d = r.json()["data"]
    assert d["matiere_id"] == 1
    assert [e["examen_id"] for e in d["examens"]] == [EXAMEN_ARCHIVE, EXAMEN_CLOS]
    assert d["exclusions"]["non_clos"] == 1
    assert d["examens"][0]["origine"]["n_etudiants"] == 3
    assert d["examens"][0]["bins"] and d["examens"][0]["par_station"]
    assert d["lectures"] == []


def test_tendances_admin_toute_matiere(client_bi):
    assert client_bi.get("/ai/matieres/4/tendances", headers=ADMIN).status_code == 200


def test_tendances_admin_matiere_inconnue_200_vide_dite(client_bi):
    r = client_bi.get("/ai/matieres/999/tendances", headers=ADMIN)
    assert r.status_code == 200
    d = r.json()["data"]
    assert d["examens"] == []
    assert d["lectures"][0]["code"] == "AUCUN_EXAMEN_CLOS"


def test_tendances_plan_de_donnees_mort_503(client_bi, monkeypatch):
    def _boom(_mid):
        raise ConnectionError("postgres injoignable")
    monkeypatch.setattr("app.db.examens_par_matiere", _boom)
    r = client_bi.get("/ai/matieres/1/tendances", headers=RESP_M1)
    assert r.status_code == 503 and "indisponible" in r.json()["message"]


# ── /synthese : SUPER_ADMIN, agrégé d'abord ──────────────────────────────────

def test_synthese_sans_identite_401(client_bi):
    assert client_bi.get("/ai/faculte/synthese").status_code == 401


def test_synthese_responsable_403_nominatif(client_bi):
    r = client_bi.get("/ai/faculte/synthese", headers=RESP_M1)
    assert r.status_code == 403
    assert "SUPER_ADMIN" in r.json()["message"]


def test_synthese_evaluateur_403(client_bi):
    assert client_bi.get("/ai/faculte/synthese", headers=EVAL).status_code == 403


def test_synthese_admin_200_agrege(client_bi):
    r = client_bi.get("/ai/faculte/synthese", headers=ADMIN)
    assert r.status_code == 200
    d = r.json()["data"]
    assert [m["matiere_id"] for m in d["matieres"]] == [1, 4]
    assert d["faculte"]["nb_examens_clos"] == 3
    assert d["matieres"][0]["nb_examens_clos"] == 2
    # 6 étudiants < 10 : refus nommé, jamais un nombre nu
    assert d["matieres"][0]["mediane_sur_20"] is None
    assert "effectif insuffisant" in d["matieres"][0]["raison"]


def test_synthese_ne_sert_jamais_un_etudiant(client_bi):
    r = client_bi.get("/ai/faculte/synthese", headers=ADMIN)
    for interdit in ("etudiant_id", "saisi_par", "notation_id", "evaluateur_id"):
        assert interdit not in r.text, interdit


def test_synthese_plan_de_donnees_mort_503(client_bi, monkeypatch):
    def _boom():
        raise ConnectionError("postgres injoignable")
    monkeypatch.setattr("app.db.examens_clos_toutes_matieres", _boom)
    assert client_bi.get("/ai/faculte/synthese", headers=ADMIN).status_code == 503
