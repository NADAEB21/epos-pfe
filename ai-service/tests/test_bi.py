"""``app.bi`` — les agrégats BI (#365 / N10), fonctions pures sur le jeu
synthétique de ``conftest`` (station 9, 3 notations : totaux 16 / 4 / 18 sur 20).

Ce que ces tests verrouillent : le découpage de l'histogramme (bords, sousSeuil),
la réutilisation de ``resume`` (médiane, taux ≥ moitié), le barème délibéré
absent → ``delibere`` null, la couverture incomplète DITE, et l'invariant
structurel de la synthèse : aucun identifiant d'étudiant ne sort.
"""

import pytest

from app import bi
from app.stats import engine
from conftest import _donnees, _grilles_lignes


# ── Histogramme ──────────────────────────────────────────────────────────────

def test_bins_bords_et_seuil():
    b = bi.bins([0.0, 20.0, 10.0, 3.9], maximum=20.0, seuil=10.0)
    assert [x["label"] for x in b] == ["0–4", "4–8", "8–12", "12–16", "16–20"]
    assert [x["count"] for x in b] == [2, 0, 1, 0, 1]     # 0 → 1re, 20 → DERNIÈRE classe
    assert [x["sousSeuil"] for x in b] == [True, True, False, False, False]
    assert b[0]["pct"] == 50.0


def test_bins_sans_axe_si_max_nul():
    assert bi.bins([1.0], maximum=0.0, seuil=0.0) == []
    assert bi.bins([1.0], maximum=None, seuil=0.0) == []


def test_bins_vide_pct_zero():
    assert all(x["count"] == 0 and x["pct"] == 0.0 for x in bi.bins([], 20.0, 10.0))


# ── La carte d'une session ───────────────────────────────────────────────────

@pytest.fixture
def plan_de_donnees(monkeypatch):
    monkeypatch.setattr("app.stats.loader.charger_examen", lambda eid, **_kw: _donnees())
    monkeypatch.setattr("app.db.nb_notations_verrouillees", lambda eid: 4)
    monkeypatch.setattr("app.db.grilles_snapshot", lambda eid: _grilles_lignes())
    monkeypatch.setattr("app.db.bareme_courant", lambda eid: [])


def test_carte_reutilise_resume(plan_de_donnees):
    c = bi.resume_examen(77, "Session A", "2026-06-01", "TERMINE")
    assert c["origine"] == {
        "n_etudiants": 3, "denominateur": 20.0, "mediane": 16.0,
        "moyenne": pytest.approx(38 / 3), "taux_reussite": pytest.approx(2 / 3),
    }
    assert c["delibere"] is None and c["bareme_version"] is None
    assert c["couverture_snapshot_complete"] is True
    assert [x["count"] for x in c["bins"]] == [0, 1, 0, 0, 2]
    assert c["par_station"] == [{
        "station_id": 9, "n": 3, "echecs": 1, "taux_echec": pytest.approx(1 / 3),
        "mediane": 16.0, "note_max": 20.0,
    }]
    assert c["exclusions"] == {"saisi_par_null": 1, "detail_incomplet": 0,
                               "notations_analysees": 3, "sans_aucun_item": 1}
    assert c["lectures"] == []
    assert c["date_examen"] == "2026-06-01" and c["entrees_hash"]


def test_carte_bareme_delibere(plan_de_donnees, monkeypatch):
    """Un barème v1 qui exclut la station 9 : dénominateur délibéré 0, totaux 0
    — la dégénérescence de scoring reproduite, pas maquillée ; et la version dite."""
    monkeypatch.setattr("app.db.bareme_courant",
                        lambda eid: [(1, "EXCLURE_STATION", None, 9, None)])
    c = bi.resume_examen(77)
    assert c["bareme_version"] == 1
    assert c["delibere"]["denominateur"] == 0.0
    assert c["origine"]["mediane"] == 16.0        # l'origine ne bouge jamais


def test_carte_couverture_incomplete_dite(plan_de_donnees, monkeypatch):
    monkeypatch.setattr("app.db.grilles_snapshot", lambda eid: [])
    c = bi.resume_examen(77)
    assert c["couverture_snapshot_complete"] is False
    assert c["origine"]["mediane"] is None and c["bins"] == []
    assert [x["code"] for x in c["lectures"]] == [bi.COUVERTURE_INCOMPLETE]
    # la station reste lisible : note_max de la grille vivante
    assert c["par_station"][0]["note_max"] == 20.0


def test_carte_sans_notation(plan_de_donnees, monkeypatch):
    d = _donnees()
    d.notations.clear()
    monkeypatch.setattr("app.stats.loader.charger_examen", lambda eid, **_kw: d)
    c = bi.resume_examen(77)
    assert c["origine"]["n_etudiants"] == 0
    assert [x["code"] for x in c["lectures"]] == [bi.SANS_NOTATION]


# ── Tendances : périmètre par le snapshot, exclusions comptées ───────────────

def test_tendances_filtre_et_compte(plan_de_donnees, monkeypatch):
    monkeypatch.setattr("app.db.examens_par_matiere", lambda mid: [
        (10, "TERMINE", "Mars", "2026-03-01"),
        (11, "EN_COURS", "Avril", "2026-04-01"),
        (12, "ARCHIVE", "Janvier", "2026-01-01"),
        (13, "TERMINE", "Sans snapshot", "2026-05-01"),
        (14, "TERMINE", "Dérivé", "2026-06-01"),
    ])
    snap = {10: 1, 12: 1, 13: None, 14: 4}
    monkeypatch.setattr("app.db.resolve_matiere", lambda eid: snap.get(eid))
    t = bi.tendances_matiere(1)
    assert [c["examen_id"] for c in t["examens"]] == [12, 10]   # ordre des dates
    assert t["exclusions"] == {"non_clos": 1, "sans_snapshot": 1, "hors_snapshot": 1}
    assert t["lectures"] == []


def test_tendances_vide_dite(plan_de_donnees, monkeypatch):
    monkeypatch.setattr("app.db.examens_par_matiere", lambda mid: [])
    t = bi.tendances_matiere(999)
    assert t["examens"] == []
    assert [x["code"] for x in t["lectures"]] == [bi.AUCUN_EXAMEN_CLOS]


# ── Synthèse : agrégé d'abord ────────────────────────────────────────────────

CLES_INTERDITES = {"etudiant_id", "etudiants", "saisi_par", "notation_id", "evaluateur_id"}


def _cles(node, out: set):
    if isinstance(node, dict):
        for k, v in node.items():
            out.add(k)
            _cles(v, out)
    elif isinstance(node, list):
        for v in node:
            _cles(v, out)
    return out


def _sessions_faculte(monkeypatch):
    monkeypatch.setattr("app.db.examens_clos_toutes_matieres", lambda: [
        (10, 1, "TERMINE", "M1 mars", "2026-03-01"),
        (11, 1, "TERMINE", "M1 juin", "2026-06-01"),
        (12, 1, "ARCHIVE", "M1 janv", "2026-01-01"),
        (13, 1, "TERMINE", "M1 sept", "2026-09-01"),
        (20, 4, "TERMINE", "M4", "2026-02-01"),
        (30, 7, "TERMINE", "Fantôme", "2026-02-01"),
    ])
    snap = {10: 1, 11: 1, 12: 1, 13: 1, 20: 4}
    monkeypatch.setattr("app.db.resolve_matiere", lambda eid: snap.get(eid))


def test_synthese_agregats_et_refus(plan_de_donnees, monkeypatch):
    _sessions_faculte(monkeypatch)
    s = bi.synthese_faculte()
    m1, m4 = s["matieres"]
    # matière 1 : 4 sessions × 3 étudiants = 12 ≥ 10 → concluant, pooling /20
    assert m1["matiere_id"] == 1 and m1["nb_examens_clos"] == 4
    assert m1["n_etudiants"] == 12 and m1["statut"] == engine.CONCLUANT
    assert m1["mediane_sur_20"] == 16.0 and m1["taux_reussite"] == pytest.approx(2 / 3)
    assert m1["dernier_examen"] == {"examen_id": 13, "nom": "M1 sept", "date_examen": "2026-09-01"}
    assert [x["examen_id"] for x in m1["sessions"]] == [12, 10, 11, 13]
    # matière 4 : 3 étudiants < 10 → refus nommé, aucun nombre nu
    assert m4["statut"] == engine.NON_CONCLUANT and m4["mediane_sur_20"] is None
    assert m4["raison"] == "non concluant — effectif insuffisant (n=3 < 10)"
    assert s["faculte"]["nb_matieres"] == 2 and s["faculte"]["nb_examens_clos"] == 5
    assert s["faculte"]["n_etudiants"] == 15
    assert s["exclusions"] == {"sans_snapshot": 1}


def test_synthese_jamais_par_etudiant(plan_de_donnees, monkeypatch):
    """L'invariant D5, structurel : aucune clé nominative dans tout l'arbre."""
    _sessions_faculte(monkeypatch)
    cles = _cles(bi.synthese_faculte(), set())
    assert not (cles & CLES_INTERDITES), cles & CLES_INTERDITES


def test_synthese_derive_vivante_comptee(plan_de_donnees, monkeypatch):
    """Matière vivante 1 mais snapshot 4 : l'examen est classé chez 4 ET compté."""
    monkeypatch.setattr("app.db.examens_clos_toutes_matieres",
                        lambda: [(10, 1, "TERMINE", "X", "2026-03-01")])
    monkeypatch.setattr("app.db.resolve_matiere", lambda eid: 4)
    s = bi.synthese_faculte()
    assert [m["matiere_id"] for m in s["matieres"]] == [4]
    assert s["matieres"][0]["hors_snapshot"] == 1
