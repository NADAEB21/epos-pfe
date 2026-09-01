"""Les déclencheurs du moteur de proposition (#362) — NOS seuils, et le
contrat de refus appliqué aux propositions : rien ne part d'un indice
NON_CONCLUANT ; la discrimination n'est lue que contre un total cohérent.

Les indices sont construits à la main (forme de ``runner.calculer_indices``) :
on teste le déclencheur, pas le moteur N5 (jugé ailleurs).
"""

from app.bareme import projection as pj
from app.bareme import propositions as props
from app.stats.loader import DonneesExamen
from app.stats.types import CONCLUANT, NON_CONCLUANT, CritereDef, Exclusions, NotationChargee


def indice(code, valeur=None, statut=CONCLUANT, n=36, details=None, raison=None):
    return {"code": code, "statut": statut, "n": n, "valeur": valeur,
            "ic": [valeur - 0.1, valeur + 0.1] if valeur is not None else None,
            "raison": raison, "details": details or {}}


def critere(item_id, libelle, grille, station, p, r, type_="BINAIRE",
            p_statut=CONCLUANT, r_statut=CONCLUANT):
    return {
        "item_id": item_id, "libelle": libelle, "type": type_, "grille_id": grille, "station_id": station,
        "difficulte": indice("DIFFICULTE", p, p_statut),
        "discrimination": indice("DISCRIMINATION", r, r_statut),
    }


def grille(grille_id, station, alpha, statut=CONCLUANT):
    return {"grille_id": grille_id, "station_id": station,
            "alpha_cronbach": indice("ALPHA_CRONBACH", alpha, statut, details={"k": 4})}


def station(station_id, taux, taux_autres, p_value, statut=CONCLUANT):
    return {"station_id": station_id, "concentration_echec": indice(
        "CONCENTRATION_ECHEC", taux, statut,
        details={"taux_autres": taux_autres, "p_value": p_value, "echecs_station": int(taux * 36), "n_autres": 72},
    )}


def payload(par_critere=(), par_grille=(), par_station=()):
    return {"examen_id": 80, "par_critere": list(par_critere), "par_grille": list(par_grille),
            "par_station": list(par_station)}


# ── Op. 1 : critère impossible (p ≤ 0,10) ────────────────────────────────────

def test_critere_impossible_declenche_rang_1():
    liste, lectures = props.candidats(payload(
        par_critere=[critere(222, "Critère impossible", 94, 107, p=0.056, r=0.115)],
        par_grille=[grille(94, 107, 0.06)],
    ))
    assert len(liste) == 1
    c = liste[0]
    assert c["operation"] == pj.Operation(pj.EXCLURE_CRITERE, cible_item_id=222)
    assert c["rang_defendabilite"] == 1 and c["lecture_code"] == props.CRITERE_IMPOSSIBLE
    assert c["declencheur"][0]["code"] == "DIFFICULTE" and c["declencheur"][0]["seuil"] == 0.10
    assert lectures == []


def test_p_au_dessus_du_seuil_ne_declenche_pas():
    liste, _ = props.candidats(payload(par_critere=[critere(1, "Dur mais possible", 94, 107, p=0.11, r=0.4)],
                                       par_grille=[grille(94, 107, 0.7)]))
    assert liste == []


def test_non_concluant_ne_declenche_jamais():
    """Le contrat de refus s'applique aux propositions : p=0.02 NON_CONCLUANT (n<10) → rien."""
    liste, _ = props.candidats(payload(
        par_critere=[critere(1, "Petit n", 94, 107, p=0.02, r=0.0, p_statut=NON_CONCLUANT, r_statut=NON_CONCLUANT)],
        par_grille=[grille(94, 107, 0.8)],
    ))
    assert liste == []


# ── Op. 1 : critère sans lien (|r| ≤ 0,10), GARDÉ par α ≥ 0,50 ──────────────

def test_r_nul_avec_total_coherent_declenche():
    liste, lectures = props.candidats(payload(
        par_critere=[critere(228, "Étape 2", 96, 109, p=0.44, r=0.05)],
        par_grille=[grille(96, 109, 0.59)],
    ))
    assert len(liste) == 1
    assert liste[0]["lecture_code"] == props.CRITERE_SANS_LIEN
    codes = [d["code"] for d in liste[0]["declencheur"]]
    assert codes == ["DISCRIMINATION", "ALPHA_CRONBACH"]
    assert lectures == []


def test_r_nul_sous_alpha_faible_devient_lecture_grille_sans_proposition():
    """La Station Défauts de l'examen 80 : α=0.06, deux critères à r≈0 sur 4
    (dont un SAIN) — indistinguables. Aucun retrait d'item ; la grille est dite
    incohérente. Le critère impossible, lui, part par la difficulté."""
    liste, lectures = props.candidats(payload(
        par_critere=[
            critere(221, "Geste conforme", 94, 107, p=0.50, r=-0.026),
            critere(222, "Critère impossible", 94, 107, p=0.056, r=0.115),
            critere(223, "Critère sans lien", 94, 107, p=0.556, r=-0.084),
            critere(224, "Précision du geste", 94, 107, p=0.494, r=0.271, type_="NUMERIQUE"),
        ],
        par_grille=[grille(94, 107, 0.0597)],
    ))
    assert [c["operation"].cible_item_id for c in liste] == [222]
    assert len(lectures) == 1
    lect = lectures[0]
    assert lect["code"] == props.GRILLE_INCOHERENTE
    assert lect["grille_id"] == 94 and lect["station_id"] == 107
    assert lect["details"]["items_r_nul"] == [221, 223] and lect["details"]["k"] == 4


def test_r_nul_minoritaire_sous_alpha_faible_ni_proposition_ni_lecture():
    """Un seul critère à r≈0 sur 4 avec α faible : pas la moitié → pas de
    lecture GRILLE_INCOHERENTE ; et pas de proposition (α sous la référence)."""
    liste, lectures = props.candidats(payload(
        par_critere=[critere(1, "a", 94, 107, p=0.5, r=0.02), critere(2, "b", 94, 107, p=0.5, r=0.4),
                     critere(3, "c", 94, 107, p=0.5, r=0.5), critere(4, "d", 94, 107, p=0.5, r=0.3)],
        par_grille=[grille(94, 107, 0.3)],
    ))
    assert liste == [] and lectures == []


def test_r_nul_sans_alpha_concluant_ne_declenche_pas():
    liste, lectures = props.candidats(payload(
        par_critere=[critere(1, "a", 95, 108, p=0.5, r=0.0)],
        par_grille=[grille(95, 108, None, statut=NON_CONCLUANT)],
    ))
    assert liste == [] and lectures == []


# ── Op. 2 : station en échec ─────────────────────────────────────────────────

def test_station_en_echec_declenche_rang_2():
    liste, _ = props.candidats(payload(par_station=[station(107, taux=0.72, taux_autres=0.42, p_value=0.0005)]))
    assert len(liste) == 1
    c = liste[0]
    assert c["operation"] == pj.Operation(pj.EXCLURE_STATION, cible_station_id=107)
    assert c["rang_defendabilite"] == 2 and c["lecture_code"] == props.STATION_EN_ECHEC
    assert c["declencheur"][0]["p_value"] == 0.0005 and c["declencheur"][0]["taux_autres"] == 0.42


def test_station_majoritairement_en_echec_mais_pas_plus_que_les_autres():
    liste, _ = props.candidats(payload(par_station=[station(109, taux=0.58, taux_autres=0.60, p_value=0.9)]))
    assert liste == []


def test_station_significative_mais_minoritaire_en_echec():
    liste, _ = props.candidats(payload(par_station=[station(108, taux=0.40, taux_autres=0.10, p_value=0.001)]))
    assert liste == []


def test_station_non_concluante_ne_declenche_pas():
    liste, _ = props.candidats(payload(par_station=[station(108, taux=0.9, taux_autres=0.1, p_value=0.001, statut=NON_CONCLUANT)]))
    assert liste == []


def test_ordre_par_rang_de_defendabilite():
    liste, _ = props.candidats(payload(
        par_critere=[critere(222, "x", 94, 107, p=0.05, r=0.1)],
        par_grille=[grille(94, 107, 0.1)],
        par_station=[station(107, taux=0.72, taux_autres=0.42, p_value=0.0005)],
    ))
    assert [c["rang_defendabilite"] for c in liste] == [1, 2]


# ── construire — assemblage, validation scoring, effet, journal ──────────────

ITEMS_JSON = ('[{"id": 1, "type": "BINAIRE", "ponderation": 5.0, "valeurMax": null},'
              ' {"id": 2, "type": "NUMERIQUE", "ponderation": 1.0, "valeurMax": 15.0}]')
CRITERES = {
    1: CritereDef(1, "Impossible", "BINAIRE", 5.0, None, 94, 107, 20.0),
    2: CritereDef(2, "Sans max", "NUMERIQUE", 1.0, None, 94, 107, 20.0),
    3: CritereDef(3, "Autre station", "BINAIRE", 20.0, None, 96, 109, 20.0),
}
GRILLES = {107: pj.GrilleSnap(107, 94, 20.0, ITEMS_JSON)}


def donnees(avec_station_109=False):
    notations = [
        NotationChargee(1, 107, 94, 500, 67, 12.0, {1: 0.0, 2: 12.0}),
        NotationChargee(2, 107, 94, 501, 67, 6.0, {1: 1.0, 2: 1.0}),
    ]
    if avec_station_109:
        notations.append(NotationChargee(3, 500, 96, 500, 67, 20.0, {3: 1.0}))
        notations[-1] = NotationChargee(3, 109, 96, 500, 67, 20.0, {3: 1.0})
    return DonneesExamen(criteres=CRITERES, notations=notations,
                         exclusions=Exclusions(notations_analysees=len(notations)))


def construire(indices, courant=None, grilles=GRILLES, d=None, decisions=None):
    return props.construire(
        examen_id=80, entrees_hash="a" * 64, moteur_version="n8-test",
        donnees=d or donnees(), indices=indices, courant=courant, grilles=grilles,
        decisions=decisions or {},
    )


def test_construire_proposition_complete_et_journalisable():
    out = construire(payload(par_critere=[critere(1, "Impossible", 94, 107, p=0.05, r=0.1)],
                             par_grille=[grille(94, 107, 0.2)]))
    assert out["couverture_snapshot_complete"] is True and out["bareme_courant"] is None
    assert len(out["propositions"]) == 1
    p = out["propositions"][0]
    assert p["operation"] == {"type": "EXCLURE_CRITERE", "cibleItemId": 1, "cibleStationId": None, "nouvelleEchelle": None}
    assert p["operations_a_soumettre"] == [p["operation"]]
    assert p["cible"]["max"] == 5.0 and p["cible"]["libelle"] == "Impossible"
    assert p["deja_appliquee"] is False and p["decision"] is None
    # effet : 12 et 6 sur 20 → exclure l'item 1 (0 et 5) → 12 et 1 sur 15.
    e = p["effet_projete"]
    assert e["avant"]["denominateur"] == 20.0 and e["avant"]["mediane"] == 9.0
    assert e["apres"]["denominateur"] == 15.0 and e["apres"]["mediane"] == 6.5
    assert e["avant"]["taux_reussite"] == 0.5 and e["apres"]["taux_reussite"] == 0.5
    lignes = props.lignes_journal(out)
    assert lignes[0]["proposition_id"] == p["proposition_id"] and lignes[0]["bareme_version_base"] is None
    assert len(p["proposition_id"]) == 32


def test_proposition_id_deterministe_et_sensible():
    o = pj.Operation(pj.EXCLURE_CRITERE, cible_item_id=1)
    a = props.proposition_id(80, "h1", "m", None, o)
    assert a == props.proposition_id(80, "h1", "m", None, o)
    assert a != props.proposition_id(80, "h2", "m", None, o)      # données changées
    assert a != props.proposition_id(80, "h1", "m", 1, o)         # sur une v1
    assert a != props.proposition_id(80, "h1", "m2", None, o)     # moteur changé


def test_cible_sans_valeur_max_non_deliberable_dite():
    out = construire(payload(par_critere=[critere(2, "Sans max", 94, 107, p=0.05, r=0.1, type_="NUMERIQUE")],
                             par_grille=[grille(94, 107, 0.2)]),
                     grilles={107: pj.GrilleSnap(107, 94, 20.0, '[{"id": 1, "type": "BINAIRE", "ponderation": 5.0}]')})
    assert out["propositions"] == []
    lect = [l for l in out["lectures_sans_proposition"] if l["code"] == props.CIBLE_NON_DELIBERABLE]
    assert len(lect) == 1 and lect[0]["details"]["refus_scoring"] == "CIBLE_ITEM_SANS_VALEUR_MAX"
    assert lect[0]["lecture_code"] == props.CRITERE_IMPOSSIBLE


def test_station_non_snapshotee_dite_et_couverture_incomplete():
    out = construire(payload(par_critere=[critere(3, "Autre station", 96, 109, p=0.05, r=0.1)],
                             par_grille=[grille(96, 109, 0.2)]),
                     d=donnees(avec_station_109=True))
    assert out["couverture_snapshot_complete"] is False
    assert out["propositions"] == []
    codes = [l["code"] for l in out["lectures_sans_proposition"]]
    assert props.STATION_NON_SNAPSHOTEE in codes and props.COUVERTURE_INCOMPLETE in codes


def test_deja_appliquee_lue_dans_la_donnee():
    courant = pj.BaremeCourant(version=1, operations=(pj.Operation(pj.EXCLURE_CRITERE, cible_item_id=1),))
    out = construire(payload(par_critere=[critere(1, "Impossible", 94, 107, p=0.05, r=0.1)],
                             par_grille=[grille(94, 107, 0.2)]), courant=courant)
    p = out["propositions"][0]
    assert p["deja_appliquee"] is True
    assert p["operations_a_soumettre"] == [p["operation"]]  # la version courante, inchangée
    assert p["effet_projete"]["avant"] == p["effet_projete"]["apres"]
    assert out["bareme_courant"] == {"version": 1, "operations": [p["operation"]]}


def test_operations_a_soumettre_cumule_la_version_courante():
    """Une v1 (station repondérée ailleurs) existe : la proposition s'AJOUTE
    (versions complètes, ADR-0030 D3) et « avant » est la v1."""
    courant = pj.BaremeCourant(version=1, operations=(pj.Operation(pj.REPONDERER, cible_item_id=2, nouvelle_echelle=10.0),))
    out = construire(payload(par_critere=[critere(1, "Impossible", 94, 107, p=0.05, r=0.1)],
                             par_grille=[grille(94, 107, 0.2)]), courant=courant)
    p = out["propositions"][0]
    assert p["operations_a_soumettre"] == [courant.operations[0].as_wire(), p["operation"]]
    assert p["effet_projete"]["avant"]["denominateur"] == 15.0   # 20 + (10 − 15)
    assert p["effet_projete"]["apres"]["denominateur"] == 10.0   # − 5 (BINAIRE exclu)
    assert p["effet_projete"]["origine"]["denominateur"] == 20.0


def test_decision_du_journal_attachee():
    o = pj.Operation(pj.EXCLURE_CRITERE, cible_item_id=1)
    pid = props.proposition_id(80, "a" * 64, "n8-test", None, o)
    out = construire(payload(par_critere=[critere(1, "Impossible", 94, 107, p=0.05, r=0.1)],
                             par_grille=[grille(94, 107, 0.2)]),
                     decisions={pid: {"proposition_id": pid, "operation": o.as_wire(),
                                      "decision": "REFUSER", "motif": "on garde", "decide_par": 5,
                                      "decide_a": "2026-09-01T10:05:00+00:00", "bareme_version_resultat": None}})
    assert out["propositions"][0]["decision"]["decision"] == "REFUSER"
    assert out["propositions"][0]["decision"]["motif"] == "on garde"
    assert out["propositions"][0]["decision"]["proposition_id"] == pid


def test_ligne_ouverte_du_journal_n_est_pas_une_decision():
    o = pj.Operation(pj.EXCLURE_CRITERE, cible_item_id=1)
    pid = props.proposition_id(80, "a" * 64, "n8-test", None, o)
    out = construire(payload(par_critere=[critere(1, "Impossible", 94, 107, p=0.05, r=0.1)],
                             par_grille=[grille(94, 107, 0.2)]),
                     decisions={pid: {"proposition_id": pid, "operation": o.as_wire(), "decision": None,
                                      "motif": None, "decide_par": None, "decide_a": None}})
    assert out["propositions"][0]["decision"] is None


def test_deja_appliquee_retrouve_sa_decision_par_operation_et_n_est_pas_rejournalisee():
    """Après acceptation, la version de base passe None → 1 : l'id change. La
    décision ACCEPTER de l'ancien id doit rester visible, et l'opération déjà
    appliquée ne génère pas de nouvelle ligne de journal (rien à décider)."""
    o = pj.Operation(pj.EXCLURE_CRITERE, cible_item_id=1)
    ancien = props.proposition_id(80, "a" * 64, "n8-test", None, o)
    courant = pj.BaremeCourant(version=1, operations=(o,))
    out = construire(payload(par_critere=[critere(1, "Impossible", 94, 107, p=0.05, r=0.1)],
                             par_grille=[grille(94, 107, 0.2)]), courant=courant,
                     decisions={ancien: {"proposition_id": ancien, "operation": o.as_wire(),
                                         "decision": "ACCEPTER", "motif": "ok", "decide_par": 5,
                                         "decide_a": "2026-09-01T10:05:00+00:00", "bareme_version_resultat": 1}})
    p = out["propositions"][0]
    assert p["deja_appliquee"] is True and p["proposition_id"] != ancien
    assert p["decision"]["decision"] == "ACCEPTER" and p["decision"]["proposition_id"] == ancien
    assert props.lignes_journal(out) == []


def test_exclure_station_retire_les_operations_critere_de_la_station():
    """v1 exclut le critère 1 (station 107) ; proposer d'exclure la station 107
    ne doit pas soumettre les deux niveaux (scoring refuse « niveaux mélangés ») :
    la composition retire l'opération critère devenue caduque."""
    courant = pj.BaremeCourant(version=1, operations=(pj.Operation(pj.EXCLURE_CRITERE, cible_item_id=1),))
    out = construire(payload(par_station=[station(107, taux=0.72, taux_autres=0.42, p_value=0.0005)]),
                     courant=courant)
    assert len(out["propositions"]) == 1
    p = out["propositions"][0]
    assert p["operation"]["type"] == "EXCLURE_STATION"
    assert p["operations_a_soumettre"] == [p["operation"]]
    assert p["effet_projete"]["avant"]["denominateur"] == 15.0   # v1 : 20 − 5
    assert p["effet_projete"]["apres"]["denominateur"] == 0.0    # la seule station sort


def test_exclure_station_conserve_les_operations_des_autres_stations():
    autre = pj.Operation(pj.REPONDERER, cible_station_id=999, nouvelle_echelle=10.0)
    meme = pj.Operation(pj.REPONDERER, cible_station_id=107, nouvelle_echelle=10.0)
    composee = props._composer([autre, meme], pj.Operation(pj.EXCLURE_STATION, cible_station_id=107), CRITERES)
    assert composee == [autre, pj.Operation(pj.EXCLURE_STATION, cible_station_id=107)]


def test_reponderation_toujours_dite_jamais_proposee():
    out = construire(payload())
    codes = [l["code"] for l in out["lectures_sans_proposition"]]
    assert codes == [props.REPONDERATION_JAMAIS_AUTOMATIQUE]
    assert out["seuils"]["alpha_reference"] == 0.5


def test_bareme_depuis_lignes_vide_vs_absent():
    assert props.bareme_depuis_lignes([]) is None
    vide = props.bareme_depuis_lignes([(2, None, None, None, None)])
    assert vide == pj.BaremeCourant(version=2, operations=())
    v = props.bareme_depuis_lignes([(3, "EXCLURE_CRITERE", 1, None, None), (3, "REPONDERER", None, 107, 10.0)])
    assert v.version == 3 and len(v.operations) == 2
    assert v.operations[1] == pj.Operation("REPONDERER", cible_station_id=107, nouvelle_echelle=10.0)
