"""Les routes de l'étage C (#362) à travers HTTP : /propositions, /decision,
/projection. Ce que ces tests verrouillent : l'ordre des refus hérité
(401→404→403→409), l'écriture STRICTE du journal (503 si ai_db tombe),
l'acte journalisé UNE fois (accepter ET refuser), l'identité obligatoire
d'une décision (X-User-Id), la proposition périmée (409), et la projection
comme pure lecture.

Le petit examen de conftest (n=3) ne déclenche rien de lui-même (tout est
NON_CONCLUANT — le contrat de refus) : le déclencheur est injecté par
``avec_candidat``, les déclencheurs eux-mêmes sont jugés dans
``test_propositions_triggers``.
"""

import pytest

from app.bareme import projection as pj
from app.bareme import propositions as props
from conftest import (
    ADMIN_ID, EVAL, RESP_M1, RESP_M1_ID, RESP_M2,
    EXAMEN_CLOS, EXAMEN_EN_COURS, EXAMEN_HORS_MATIERE,
)

PROPS = f"/ai/examens/{EXAMEN_CLOS}/propositions"


@pytest.fixture
def avec_candidat(monkeypatch):
    """Injecte un candidat rang 1 (exclure l'item 2 BINAIRE de la station 9)."""
    def candidats(_indices):
        return ([{
            "operation": pj.Operation(pj.EXCLURE_CRITERE, cible_item_id=2),
            "rang_defendabilite": 1, "lecture_code": props.CRITERE_IMPOSSIBLE,
            "cible": {"item_id": 2, "libelle": "EPI", "type": "BINAIRE", "grille_id": 5, "station_id": 9},
            "declencheur": [{"code": "DIFFICULTE", "valeur": 0.05, "ic": [0.0, 0.1], "n": 36, "seuil": 0.1}],
        }], [])
    monkeypatch.setattr("app.bareme.propositions.candidats", candidats)


def decision_url(pid):
    return f"{PROPS}/{pid}/decision"


# ── GET /propositions : gardes héritées ──────────────────────────────────────

def test_sans_identite_401(client):
    assert client.get(PROPS).status_code == 401


def test_examen_inconnu_404(client):
    assert client.get("/ai/examens/999/propositions", headers=RESP_M1).status_code == 404


def test_hors_matiere_403(client):
    assert client.get(f"/ai/examens/{EXAMEN_HORS_MATIERE}/propositions", headers=RESP_M1).status_code == 403


def test_evaluateur_403(client):
    assert client.get(PROPS, headers=EVAL).status_code == 403


def test_non_clos_409_nomme_les_propositions(client):
    r = client.get(f"/ai/examens/{EXAMEN_EN_COURS}/propositions", headers=RESP_M1)
    assert r.status_code == 409
    assert "propositions" in r.json()["message"] and "EN_COURS" in r.json()["message"]


def test_hors_matiere_prime_sur_non_clos(client, monkeypatch):
    monkeypatch.setattr("app.db.statut_examen", lambda eid: "EN_COURS")
    assert client.get(f"/ai/examens/{EXAMEN_HORS_MATIERE}/propositions", headers=RESP_M1).status_code == 403


# ── GET /propositions : contenu et journal ───────────────────────────────────

def test_petit_examen_aucune_proposition_mais_le_silence_est_dit(client, faux_journal):
    r = client.get(PROPS, headers=RESP_M1)
    assert r.status_code == 200
    data = r.json()["data"]
    assert data["propositions"] == []
    assert data["couverture_snapshot_complete"] is True
    assert data["bareme_courant"] is None
    assert [l["code"] for l in data["lectures_sans_proposition"]] == [props.REPONDERATION_JAMAIS_AUTOMATIQUE]
    assert data["entrees_hash"] and data["moteur_version"]
    assert faux_journal.insertions == 0


def test_proposition_servie_et_journalisee_une_fois(client, faux_journal, avec_candidat):
    r1 = client.get(PROPS, headers=RESP_M1)
    assert r1.status_code == 200
    p = r1.json()["data"]["propositions"][0]
    assert p["operation"]["type"] == "EXCLURE_CRITERE" and p["operation"]["cibleItemId"] == 2
    assert p["operations_a_soumettre"] == [p["operation"]]
    # conftest : scores 16, 4, 18 sur 20 ; l'item 2 vaut 10 → 6, 4, 8 sur 10.
    assert p["effet_projete"]["avant"]["denominateur"] == 20.0
    assert p["effet_projete"]["apres"]["denominateur"] == 10.0
    assert p["effet_projete"]["apres"]["mediane"] == 6.0
    assert p["decision"] is None and p["deja_appliquee"] is False
    assert faux_journal.insertions == 1
    ligne = faux_journal.lire(p["proposition_id"])
    assert ligne["entrees_hash"] == r1.json()["data"]["entrees_hash"]
    assert ligne["effet_projete"] == p["effet_projete"]

    r2 = client.get(PROPS, headers=RESP_M1)
    assert r2.json()["data"]["propositions"][0]["proposition_id"] == p["proposition_id"]
    assert faux_journal.insertions == 1  # re-GET : même id, pas de doublon


def test_journal_mort_503_ecriture_stricte(client, faux_journal, avec_candidat):
    """Une proposition qui ne peut pas être tracée n'est pas servie (ADR-0015)."""
    faux_journal.panne = True
    r = client.get(PROPS, headers=RESP_M1)
    assert r.status_code == 503
    assert r.json()["success"] is False


def test_plan_de_donnees_mort_503(client, monkeypatch):
    def boom(_eid):
        raise ConnectionError("scoring_db injoignable")
    monkeypatch.setattr("app.db.grilles_snapshot", boom)
    assert client.get(PROPS, headers=RESP_M1).status_code == 503


def test_super_admin_lit(client, avec_candidat):
    assert client.get(PROPS, headers=ADMIN_ID).status_code == 200


# ── POST /decision ───────────────────────────────────────────────────────────

def _proposer(client):
    return client.get(PROPS, headers=RESP_M1).json()["data"]["propositions"][0]["proposition_id"]


def test_decision_sans_identite_401(client, avec_candidat):
    pid = _proposer(client)
    assert client.post(decision_url(pid), json={"decision": "REFUSER", "motif": "m"}).status_code == 401


def test_decision_sans_x_user_id_401(client, avec_candidat):
    """Une décision porte son auteur : autorités sans X-User-Id → 401, rien d'écrit."""
    pid = _proposer(client)
    r = client.post(decision_url(pid), json={"decision": "REFUSER", "motif": "m"}, headers=RESP_M1)
    assert r.status_code == 401 and "X-User-Id" in r.json()["message"]


def test_decision_evaluateur_403(client, avec_candidat):
    pid = _proposer(client)
    r = client.post(decision_url(pid), json={"decision": "REFUSER", "motif": "m"},
                    headers={**EVAL, "X-User-Id": "9"})
    assert r.status_code == 403


def test_decision_hors_matiere_403(client, avec_candidat):
    pid = _proposer(client)
    r = client.post(decision_url(pid), json={"decision": "REFUSER", "motif": "m"},
                    headers={**RESP_M2, "X-User-Id": "9"})
    assert r.status_code == 403


@pytest.mark.parametrize("corps, fragment", [
    ({"decision": "PEUT-ETRE", "motif": "m"}, "ACCEPTER ou REFUSER"),
    ({"decision": "ACCEPTER", "motif": "   "}, "motif"),
    ({"decision": "ACCEPTER"}, "motif"),
    ({"decision": "ACCEPTER", "motif": "m", "bareme_version_resultat": "un"}, "entier"),
])
def test_decision_corps_invalide_400(client, faux_journal, avec_candidat, corps, fragment):
    pid = _proposer(client)
    r = client.post(decision_url(pid), json=corps, headers=RESP_M1_ID)
    assert r.status_code == 400 and fragment in r.json()["message"]
    assert faux_journal.lire(pid)["decision"] is None


def test_decision_corps_non_json_400_enveloppe(client, avec_candidat):
    pid = _proposer(client)
    r = client.post(decision_url(pid), content=b"pas du json",
                    headers={**RESP_M1_ID, "Content-Type": "application/json"})
    assert r.status_code == 400 and r.json()["success"] is False


def test_decision_proposition_inconnue_404(client, avec_candidat):
    r = client.post(decision_url("deadbeefdeadbeef"), json={"decision": "REFUSER", "motif": "m"}, headers=RESP_M1_ID)
    assert r.status_code == 404


def test_decision_proposition_d_un_autre_examen_404(client, faux_journal, avec_candidat, monkeypatch):
    pid = _proposer(client)
    faux_journal.lignes[pid]["examen_id"] = 55  # la même proposition, rattachée ailleurs
    r = client.post(decision_url(pid), json={"decision": "REFUSER", "motif": "m"}, headers=RESP_M1_ID)
    assert r.status_code == 404


def test_accepter_journalise_avec_auteur_et_version(client, faux_journal, avec_candidat):
    pid = _proposer(client)
    r = client.post(decision_url(pid),
                    json={"decision": "ACCEPTER", "motif": "Le critère n'a été réussi par personne.",
                          "bareme_version_resultat": 1},
                    headers=RESP_M1_ID)
    assert r.status_code == 200
    d = r.json()["data"]
    assert d["decision"] == "ACCEPTER" and d["decide_par"] == 5 and d["bareme_version_resultat"] == 1
    assert d["motif"].startswith("Le critère")
    # Le GET suivant attache la décision à la proposition.
    p = client.get(PROPS, headers=RESP_M1).json()["data"]["propositions"][0]
    assert p["decision"]["decision"] == "ACCEPTER" and p["decision"]["decide_par"] == 5


def test_refuser_journalise_aussi(client, faux_journal, avec_candidat):
    pid = _proposer(client)
    r = client.post(decision_url(pid), json={"decision": "refuser", "motif": "On garde le critère."},
                    headers=RESP_M1_ID)
    assert r.status_code == 200
    assert r.json()["data"]["decision"] == "REFUSER"
    assert r.json()["data"]["bareme_version_resultat"] is None
    assert faux_journal.lire(pid)["decision"] == "REFUSER"


def test_seconde_decision_409_jamais_ecrasee(client, faux_journal, avec_candidat):
    pid = _proposer(client)
    client.post(decision_url(pid), json={"decision": "REFUSER", "motif": "m"}, headers=RESP_M1_ID)
    r = client.post(decision_url(pid), json={"decision": "ACCEPTER", "motif": "changé d'avis"}, headers=RESP_M1_ID)
    assert r.status_code == 409 and "REFUSER" in r.json()["message"]
    assert faux_journal.lire(pid)["decision"] == "REFUSER" and faux_journal.lire(pid)["motif"] == "m"


def test_proposition_perimee_409_quand_les_donnees_ont_bouge(client, faux_journal, avec_candidat, monkeypatch):
    """Réajustement entre la proposition et la décision : le hash a changé →
    on ne décide pas sur une projection qui ne vaut plus (D10)."""
    pid = _proposer(client)
    from conftest import _donnees, _notation
    def donnees_modifiees(eid, **_kw):
        d = _donnees()
        d.notations[0] = _notation(1, 67, 6.5, 1.0)
        return d
    monkeypatch.setattr("app.stats.loader.charger_examen", donnees_modifiees)
    r = client.post(decision_url(pid), json={"decision": "ACCEPTER", "motif": "m"}, headers=RESP_M1_ID)
    assert r.status_code == 409 and "périmée" in r.json()["message"]
    assert faux_journal.lire(pid)["decision"] is None


def test_decision_journal_mort_503(client, faux_journal, avec_candidat):
    pid = _proposer(client)
    faux_journal.panne = True
    assert client.post(decision_url(pid), json={"decision": "REFUSER", "motif": "m"}, headers=RESP_M1_ID).status_code == 503


def test_decision_non_clos_409(client, faux_journal, avec_candidat):
    pid = _proposer(client)
    r = client.post(f"/ai/examens/{EXAMEN_EN_COURS}/propositions/{pid}/decision",
                    json={"decision": "REFUSER", "motif": "m"}, headers=RESP_M1_ID)
    assert r.status_code == 409 and "décisions" in r.json()["message"]


# ── POST /projection ─────────────────────────────────────────────────────────

PROJ = f"/ai/examens/{EXAMEN_CLOS}/projection"


def test_projection_sans_identite_401_avant_le_corps(client):
    assert client.post(PROJ, json={}).status_code == 401


def test_projection_corps_sans_operations_400(client):
    r = client.post(PROJ, json={}, headers=RESP_M1)
    assert r.status_code == 400 and "operations" in r.json()["message"]


def test_projection_operation_refusee_par_scoring_400_nominatif(client):
    r = client.post(PROJ, json={"operations": [{"type": "EXCLURE_CRITERE", "cibleItemId": 99}]}, headers=RESP_M1)
    assert r.status_code == 400 and "CIBLE_ITEM_ABSENTE" in r.json()["message"]


def test_projection_reponderation_station_effet_exact(client, faux_journal, faux_cache):
    """Station 9 repondérée 20 → 10 : scores 16/4/18 → 8/2/9 ; rien d'écrit nulle part."""
    r = client.post(PROJ, json={"operations": [{"type": "REPONDERER", "cibleStationId": 9, "nouvelleEchelle": 10.0}]},
                    headers=RESP_M1)
    assert r.status_code == 200
    d = r.json()["data"]
    assert d["couverture_snapshot_complete"] is True
    assert d["max_delibere_par_station"] == {"9": 10.0} and d["max_original_par_station"] == {"9": 20.0}
    assert d["effet_projete"]["origine"]["mediane"] == 16.0
    assert d["effet_projete"]["apres"]["mediane"] == 8.0 and d["effet_projete"]["apres"]["denominateur"] == 10.0
    assert d["effet_projete"]["apres"]["taux_reussite"] == 2 / 3
    assert d["bareme_courant"] is None
    assert faux_journal.insertions == 0 and faux_cache.ecritures == 0


def test_projection_version_vide_identite(client):
    r = client.post(PROJ, json={"operations": []}, headers=RESP_M1)
    assert r.status_code == 200
    e = r.json()["data"]["effet_projete"]
    assert e["apres"] == e["origine"]


def test_projection_lit_le_bareme_courant(client, monkeypatch):
    monkeypatch.setattr("app.db.bareme_courant", lambda eid: [(1, "EXCLURE_CRITERE", 2, None, None)])
    r = client.post(PROJ, json={"operations": [{"type": "REPONDERER", "cibleStationId": 9, "nouvelleEchelle": 10.0}]},
                    headers=RESP_M1)
    d = r.json()["data"]
    assert d["bareme_courant"]["version"] == 1
    assert d["effet_projete"]["avant"]["denominateur"] == 10.0  # v1 : 20 − 10 (BINAIRE pond. 10)
    # Proposer EXACTEMENT la v1 → refus double application.
    r2 = client.post(PROJ, json={"operations": [{"type": "EXCLURE_CRITERE", "cibleItemId": 2}]}, headers=RESP_M1)
    assert r2.status_code == 400 and "IDENTIQUE_VERSION_COURANTE" in r2.json()["message"]
