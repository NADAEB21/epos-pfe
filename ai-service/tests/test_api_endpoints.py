"""Les endpoints /indices et /evaluateurs à travers la route HTTP (#359).

Ce que ces tests verrouillent : l'ordre des refus (401→404→403→409), le 503
bruyant sur panne du plan de données, l'enveloppe ADR-0004, le cache (« jamais
de re-calcul par requête ») et l'exclusion « sans_aucun_item » (total V23).
"""

from conftest import (
    ADMIN, EVAL, RESP_M1, RESP_M2,
    EXAMEN_ARCHIVE, EXAMEN_CLOS, EXAMEN_EN_COURS, EXAMEN_HORS_MATIERE,
)


# ── Garde : périmètre puis clôture ───────────────────────────────────────────

def test_sans_identite_401(client):
    r = client.get(f"/ai/examens/{EXAMEN_CLOS}/indices")
    assert r.status_code == 401
    assert r.json()["success"] is False


def test_examen_inconnu_404(client):
    r = client.get("/ai/examens/999/indices", headers=RESP_M1)
    assert r.status_code == 404


def test_hors_matiere_403_nominatif(client):
    r = client.get(f"/ai/examens/{EXAMEN_HORS_MATIERE}/indices", headers=RESP_M1)
    assert r.status_code == 403
    assert "matière 4" in r.json()["message"]


def test_evaluateur_403(client):
    r = client.get(f"/ai/examens/{EXAMEN_CLOS}/indices", headers=EVAL)
    assert r.status_code == 403


def test_examen_non_clos_409_nominatif(client):
    """La garde D2 : état PERSISTÉ, message qui nomme le statut — aucune horloge."""
    r = client.get(f"/ai/examens/{EXAMEN_EN_COURS}/indices", headers=RESP_M1)
    assert r.status_code == 409
    assert "EN_COURS" in r.json()["message"]
    assert "clos" in r.json()["message"]


def test_hors_matiere_prime_sur_non_clos(client, monkeypatch):
    """On ne révèle pas le statut d'un examen hors périmètre : 403 AVANT 409."""
    monkeypatch.setattr("app.db.statut_examen", lambda eid: "EN_COURS")
    r = client.get(f"/ai/examens/{EXAMEN_HORS_MATIERE}/indices", headers=RESP_M1)
    assert r.status_code == 403


def test_archive_est_clos(client):
    r = client.get(f"/ai/examens/{EXAMEN_ARCHIVE}/indices", headers=RESP_M1)
    assert r.status_code == 200


# ── 503 bruyant (ADR-0029 D7) ────────────────────────────────────────────────

def test_plan_de_donnees_mort_503(client, monkeypatch):
    def _boom(_eid):
        raise ConnectionError("postgres injoignable")

    monkeypatch.setattr("app.db.statut_examen", _boom)
    r = client.get(f"/ai/examens/{EXAMEN_CLOS}/indices", headers=RESP_M1)
    assert r.status_code == 503
    assert "indisponible" in r.json()["message"]


def test_cache_mort_503_pas_de_repli(client, monkeypatch):
    """ai_db injoignable → 503, PAS un recalcul silencieux : la garantie de
    reproductibilité (tout ce qui est affiché existe hashé) est obligatoire."""
    def _boom(*_a, **_kw):
        raise ConnectionError("ai_db injoignable")

    monkeypatch.setattr("app.cache.lire", _boom)
    r = client.get(f"/ai/examens/{EXAMEN_CLOS}/indices", headers=RESP_M1)
    assert r.status_code == 503


# ── Le nominal : contenu, cache, exclusions ──────────────────────────────────

def test_indices_nominal_enveloppe_et_contenu(client):
    r = client.get(f"/ai/examens/{EXAMEN_CLOS}/indices", headers=RESP_M1)
    assert r.status_code == 200
    corps = r.json()
    assert corps["success"] is True
    data = corps["data"]
    assert data["examen_id"] == EXAMEN_CLOS
    assert data["moteur_version"]
    assert len(data["entrees_hash"]) == 64  # sha256 hex
    # n=3 < seuils → chaque indice porte le contrat de refus, jamais un nombre nu.
    for c in data["par_critere"]:
        assert c["difficulte"]["statut"] == "NON_CONCLUANT"
        assert "effectif insuffisant" in c["difficulte"]["raison"]


def test_exclusion_sans_aucun_item_comptee(client):
    """4 verrouillées en base (total V23), 3 visibles de la vue → 1 invisible, DITE."""
    r = client.get(f"/ai/examens/{EXAMEN_CLOS}/indices", headers=RESP_M1)
    assert r.json()["data"]["exclusions"]["sans_aucun_item"] == 1


def test_deuxieme_appel_sert_le_cache(client, faux_cache, monkeypatch):
    """« Jamais de re-calcul par requête sur le chemin chaud » (D3)."""
    calculs = []
    import app.stats.runner as runner_module
    vrai = runner_module.calculer_indices

    def espion(eid, donnees=None):
        calculs.append(eid)
        return vrai(eid, donnees)

    monkeypatch.setattr("app.stats.runner.calculer_indices", espion)
    client.get(f"/ai/examens/{EXAMEN_CLOS}/indices", headers=RESP_M1)
    client.get(f"/ai/examens/{EXAMEN_CLOS}/indices", headers=RESP_M1)
    assert calculs == [EXAMEN_CLOS]  # un seul calcul, le second appel lit la ligne
    assert faux_cache.ecritures == 1


def test_evaluateurs_meme_cache_meme_empreinte(client, faux_cache):
    """/evaluateurs sert l'autre vue de la MÊME ligne de cache que /indices."""
    r1 = client.get(f"/ai/examens/{EXAMEN_CLOS}/indices", headers=ADMIN)
    r2 = client.get(f"/ai/examens/{EXAMEN_CLOS}/evaluateurs", headers=ADMIN)
    assert r1.status_code == r2.status_code == 200
    assert r1.json()["data"]["entrees_hash"] == r2.json()["data"]["entrees_hash"]
    assert len(faux_cache.lignes) == 1


def test_evaluateurs_seul_evaluateur_refus_nomme(client):
    """Station à évaluateur unique (cas nominal #163) → refus NOMMÉ, jamais un rang.
    La notation pré-V15 (saisi_par NULL) est hors population ET comptée."""
    r = client.get(f"/ai/examens/{EXAMEN_CLOS}/evaluateurs", headers=RESP_M1)
    data = r.json()["data"]
    assert data["exclusions"]["saisi_par_null"] == 1
    station = data["par_station"][0]
    assert station["nb_evaluateurs"] == 1
    severite = station["evaluateurs"][0]["severite"]
    assert severite["statut"] == "NON_CONCLUANT"
    assert "un seul évaluateur" in severite["raison"]
