"""Le jumeau Python de BaremeDeliberationEngine (#362) — jugé contre les 17
cas de ``BaremeDeliberationEngineTest`` (scoring), valeurs calculées à la main
côté Java. Si un cas diverge ici, l'effet projeté AVANT décision ne vaudrait
plus ce que /results servira APRÈS — la faute D10 exacte.

Fixture Java reproduite : station 101, grille 201, note_max 20. Trois feuilles :
item 1 NUMERIQUE valeurMax 8 · item 2 BINAIRE pondération 5 (niché sous un
parent 10 dans items_json — exerce la récursion sousCriteres) · item 3
NUMERIQUE valeurMax 7. Notation : score_final 15.5 (≠ somme des items — un
réajustement ADR-0013 au TOTAL doit survivre au delta), valeurs item1=6,
item2=1, item3 jamais saisi.
"""

import numpy as np
import pytest

from app.bareme import projection as pj
from app.stats.loader import DonneesExamen
from app.stats.types import CritereDef, Exclusions, NotationChargee

ITEMS_JSON = """
[
  {"id": 1, "type": "NUMERIQUE", "valeurMax": 8.0, "ponderation": 1.0},
  {"id": 10, "type": "NUMERIQUE", "valeurMax": null,
   "sousCriteres": [
     {"id": 2, "type": "BINAIRE", "valeurMax": null, "ponderation": 5.0},
     {"id": 3, "type": "NUMERIQUE", "valeurMax": 7.0, "ponderation": 1.0}
   ]}
]"""

CRITERES = {
    1: CritereDef(1, "Item 1", "NUMERIQUE", 1.0, 8.0, 201, 101, 20.0),
    2: CritereDef(2, "Item 2", "BINAIRE", 5.0, None, 201, 101, 20.0),
    3: CritereDef(3, "Item 3", "NUMERIQUE", 1.0, 7.0, 201, 101, 20.0),
}
GRILLES = {101: pj.GrilleSnap(station_id=101, grille_id=201, note_max=20.0, items_json=ITEMS_JSON)}


def notation(score_final, valeurs=None) -> NotationChargee:
    return NotationChargee(notation_id=1, station_id=101, grille_id=201, etudiant_id=500,
                           saisi_par=67, score_final=score_final,
                           valeurs={1: 6.0, 2: 1.0} if valeurs is None else valeurs)


def op(type_, item=None, station=None, echelle=None) -> pj.Operation:
    return pj.Operation(type_, cible_item_id=item, cible_station_id=station, nouvelle_echelle=echelle)


def appliquer(*ops) -> pj.BaremeApplique:
    return pj.appliquer(list(ops), CRITERES, GRILLES)


# ── chargerCourant — dénominateur délibéré par station ───────────────────────

def test_version_vide_identique():
    b = appliquer()
    assert b.max_delibere_par_station[101] == 20.0
    assert b.max_original_par_station[101] == 20.0


def test_exclusion_critere_binaire_max_15():
    assert appliquer(op(pj.EXCLURE_CRITERE, item=2)).max_delibere_par_station[101] == 15.0


def test_reponderation_critere_8_vers_4_max_16():
    assert appliquer(op(pj.REPONDERER, item=1, echelle=4.0)).max_delibere_par_station[101] == 16.0


def test_exclusion_station_sort_de_la_carte():
    b = appliquer(op(pj.EXCLURE_STATION, station=101))
    assert 101 not in b.max_delibere_par_station
    assert b.stations_exclues == frozenset({101})
    # le dénominateur ORIGINAL reste entier — les deux lectures.
    assert b.max_original_par_station[101] == 20.0


def test_reponderation_station_20_vers_10():
    assert appliquer(op(pj.REPONDERER, station=101, echelle=10.0)).max_delibere_par_station[101] == 10.0


def test_operation_irresoluble_ignoree_et_dite():
    b = appliquer(op(pj.EXCLURE_CRITERE, item=99))
    assert b.operations_ignorees == (99,)
    assert b.max_delibere_par_station[101] == 20.0


# ── scoreDelibere — delta depuis score_final (jamais une re-somme) ───────────

def test_version_vide_score_identique():
    assert pj.score_delibere(appliquer(), notation(15.5)) == 15.5


def test_exclusion_binaire_15_5_moins_5():
    assert pj.score_delibere(appliquer(op(pj.EXCLURE_CRITERE, item=2)), notation(15.5)) == 10.5


def test_exclusion_critere_jamais_saisi_delta_nul_mais_max_13():
    b = appliquer(op(pj.EXCLURE_CRITERE, item=3))
    assert pj.score_delibere(b, notation(15.5)) == 15.5
    assert b.max_delibere_par_station[101] == 13.0


def test_reponderation_numerique_6_sur_8_vers_4():
    """15.5 + (6/8×4 − 6) = 12.5"""
    assert pj.score_delibere(appliquer(op(pj.REPONDERER, item=1, echelle=4.0)), notation(15.5)) == 12.5


def test_reponderation_station_15_5_sur_20_fois_10():
    assert pj.score_delibere(appliquer(op(pj.REPONDERER, station=101, echelle=10.0)), notation(15.5)) == 7.75


def test_station_exclue_none():
    assert pj.score_delibere(appliquer(op(pj.EXCLURE_STATION, station=101)), notation(15.5)) is None


def test_score_final_null_none():
    assert pj.score_delibere(appliquer(), notation(None)) is None


def test_clamp_zero_jamais_negatif():
    """score_final 3.0 < contribution 5 du critère exclu → 0, pas −2."""
    assert pj.score_delibere(appliquer(op(pj.EXCLURE_CRITERE, item=2)), notation(3.0)) == 0.0


def test_retrecissement_float32_comme_java():
    """Java : weigh() multiplie en float, scoreDelibere rend (float). Python
    float64 pur rendrait 15.2 exactement ; le jumeau doit rendre 15.2f."""
    criteres = {**CRITERES, 4: CritereDef(4, "Fin", "BINAIRE", 0.3, None, 201, 101, 20.0)}
    b = pj.appliquer([op(pj.EXCLURE_CRITERE, item=4)], criteres, GRILLES)
    s = pj.score_delibere(b, notation(15.5, valeurs={4: 1.0}))
    assert s == float(np.float32(15.2))
    assert s != 15.2


# ── valeurMaxParItem / maxDeItem — parse items_json (récursif, tolérant) ─────

def test_parse_recursif_feuilles_et_sous_criteres():
    maxes = pj.valeur_max_par_item(ITEMS_JSON)
    assert maxes == {1: 8.0, 3: 7.0}
    assert 2 not in maxes and 10 not in maxes


def test_items_json_corrompu_carte_vide():
    assert pj.valeur_max_par_item("{pas du json") == {}
    assert pj.valeur_max_par_item(None) == {}
    assert pj.valeur_max_par_item("[]") == {}


def test_max_de_item_par_type():
    maxes = pj.valeur_max_par_item(ITEMS_JSON)
    assert pj.max_de_item(CRITERES[2], maxes) == 5.0
    assert pj.max_de_item(CRITERES[1], maxes) == 8.0
    assert pj.max_de_item(CritereDef(99, "?", "NUMERIQUE", 1.0, None, 201, 101, 20.0), maxes) is None
    assert pj.max_de_item(None, maxes) is None


def test_weigh_binaire_en_float32():
    assert pj.weigh(CRITERES[2], 1.0) == 5.0
    assert pj.weigh(CRITERES[2], None) == 0.0
    assert pj.weigh(CRITERES[1], 6.0) == 6.0
    assert pj.weigh(CritereDef(4, "Fin", "BINAIRE", 0.3, None, 201, 101, 20.0), 1.0) == float(np.float32(0.3))


# ── Forme du fil ─────────────────────────────────────────────────────────────

def test_operation_wire_aller_retour_camel_case():
    o = op(pj.REPONDERER, item=1, echelle=4.0)
    wire = o.as_wire()
    assert wire == {"type": "REPONDERER", "cibleItemId": 1, "cibleStationId": None, "nouvelleEchelle": 4.0}
    assert pj.Operation.from_wire(wire) == o
    assert pj.Operation.from_wire({"type": "EXCLURE_STATION", "cibleStationId": "101"}) == op(pj.EXCLURE_STATION, station=101)


# ── valider — ce que scoring refuserait à la création ───────────────────────

SNAPSHOTES = frozenset({1, 2, 3})


def valider(*ops, courant=None):
    return pj.valider(list(ops), CRITERES, SNAPSHOTES, GRILLES, courant)


@pytest.mark.parametrize("ops, code", [
    ((op(pj.EXCLURE_CRITERE, item=99),), "CIBLE_ITEM_ABSENTE"),
    ((op(pj.EXCLURE_CRITERE, item=2, station=101),), "CIBLE_MAL_FORMEE"),
    ((op(pj.EXCLURE_STATION, station=7),), "CIBLE_STATION_ABSENTE"),
    ((op(pj.EXCLURE_STATION, item=2),), "CIBLE_MAL_FORMEE"),
    ((op(pj.REPONDERER, item=1),), "REPONDERER_SANS_ECHELLE"),
    ((op(pj.REPONDERER, item=1, echelle=0.0),), "REPONDERER_SANS_ECHELLE"),
    ((op(pj.REPONDERER, item=1, station=101, echelle=4.0),), "CIBLE_MAL_FORMEE"),
    ((op(pj.EXCLURE_CRITERE, item=2, echelle=3.0),), "ECHELLE_HORS_REPONDERER"),
    ((op(pj.EXCLURE_CRITERE, item=2), op(pj.EXCLURE_CRITERE, item=2)), "DOUBLE_CIBLE"),
    ((op(pj.EXCLURE_STATION, station=101), op(pj.EXCLURE_CRITERE, item=2)), "NIVEAUX_MELANGES"),
    ((op("INVENTE", item=2),), "TYPE_INCONNU"),
])
def test_valider_refus_nominatifs(ops, code):
    refus = valider(*ops)
    assert refus is not None and refus.code == code


def test_valider_numerique_sans_valeur_max_refuse():
    criteres = {**CRITERES, 5: CritereDef(5, "Sans max", "NUMERIQUE", 1.0, None, 201, 101, 20.0)}
    refus = pj.valider([op(pj.EXCLURE_CRITERE, item=5)], criteres, frozenset({5}), GRILLES, None)
    assert refus.code == "CIBLE_ITEM_SANS_VALEUR_MAX"


def test_valider_grille_non_snapshotee_refuse():
    criteres = {**CRITERES, 6: CritereDef(6, "Autre station", "BINAIRE", 5.0, None, 300, 150, 20.0)}
    refus = pj.valider([op(pj.EXCLURE_CRITERE, item=6)], criteres, frozenset({6}), GRILLES, None)
    assert refus.code == "GRILLE_NON_SNAPSHOTEE"


def test_valider_aucun_snapshot_refuse():
    refus = pj.valider([op(pj.EXCLURE_CRITERE, item=2)], CRITERES, frozenset(), {}, None)
    assert refus.code == "AUCUN_SNAPSHOT"


def test_valider_identique_version_courante_refuse():
    courant = pj.BaremeCourant(version=2, operations=(op(pj.EXCLURE_CRITERE, item=2),))
    refus = valider(op(pj.EXCLURE_CRITERE, item=2), courant=courant)
    assert refus.code == "IDENTIQUE_VERSION_COURANTE" and "v2" in refus.detail


def test_valider_acceptables():
    assert valider() is None
    assert valider(op(pj.EXCLURE_CRITERE, item=2)) is None
    assert valider(op(pj.EXCLURE_CRITERE, item=2), op(pj.REPONDERER, item=1, echelle=4.0)) is None
    assert valider(op(pj.REPONDERER, station=101, echelle=10.0)) is None


# ── Totaux et effet (NotationService.getResultatsByExamen) ──────────────────

def donnees(notations) -> DonneesExamen:
    return DonneesExamen(criteres=CRITERES, notations=list(notations),
                         exclusions=Exclusions(notations_analysees=len(notations)))


def n(nid, etudiant, station, grille, score, valeurs):
    return NotationChargee(nid, station, grille, etudiant, 67, score, valeurs)


GRILLES_2 = {
    101: GRILLES[101],
    102: pj.GrilleSnap(station_id=102, grille_id=202, note_max=10.0,
                       items_json='[{"id": 7, "type": "NUMERIQUE", "valeurMax": 10.0, "ponderation": 1.0}]'),
}
CRITERES_2 = {**CRITERES, 7: CritereDef(7, "Seul", "NUMERIQUE", 1.0, 10.0, 202, 102, 10.0)}


def test_totaux_denominateurs_sur_toutes_les_stations_totaux_par_etudiant():
    """L'asymétrie de scoring : dénominateur = Σ note_max des stations
    snapshotées ; total = Σ score_final des notations DE L'ÉTUDIANT."""
    d = DonneesExamen(criteres=CRITERES_2, notations=[
        n(1, 500, 101, 201, 15.5, {1: 6.0, 2: 1.0}),
        n(2, 500, 102, 202, 8.0, {7: 8.0}),
        n(3, 501, 101, 201, 4.0, {1: 4.0, 2: 0.0}),   # 501 n'a pas la station 102
    ], exclusions=Exclusions(notations_analysees=3))
    t = pj.totaux(None, d, GRILLES_2)
    assert t.couverture_complete is True
    assert t.denominateur_original == 30.0
    assert t.denominateur_delibere is None  # pas de barème ≠ barème vide
    par = {e.etudiant_id: e for e in t.etudiants}
    assert par[500].total_original == 23.5 and par[500].total_delibere is None
    assert par[501].total_original == 4.0


def test_totaux_couverture_incomplete_deniminateurs_nuls():
    d = donnees([n(1, 500, 101, 201, 15.5, {1: 6.0, 2: 1.0}), n(2, 500, 150, 300, 3.0, {})])
    t = pj.totaux(pj.appliquer([], CRITERES, GRILLES), d, GRILLES)
    assert t.couverture_complete is False
    assert t.denominateur_original is None and t.denominateur_delibere is None
    assert t.etudiants[0].total_delibere is None
    assert pj.effet(None, pj.appliquer([], CRITERES, GRILLES), d, GRILLES) is None


def test_effet_origine_avant_apres():
    """Deux étudiants (15.5 et 4.0 sur 20). Exclure l'item 2 (BINAIRE 5) :
    max 15, scores 10.5 et 4.0 → réussite (≥ 7.5) 1/2 avant comme après,
    médiane 9.75 → 7.25, dénominateur 20 → 15."""
    d = donnees([n(1, 500, 101, 201, 15.5, {1: 6.0, 2: 1.0}), n(2, 501, 101, 201, 4.0, {1: 4.0, 2: 0.0})])
    apres = pj.appliquer([op(pj.EXCLURE_CRITERE, item=2)], CRITERES, GRILLES)
    e = pj.effet(None, apres, d, GRILLES)
    assert e["origine"] == e["avant"]
    assert e["avant"]["denominateur"] == 20.0 and e["avant"]["mediane"] == 9.75
    assert e["avant"]["taux_reussite"] == 0.5 and e["avant"]["n_etudiants"] == 2
    assert e["apres"]["denominateur"] == 15.0 and e["apres"]["mediane"] == 7.25
    assert e["apres"]["taux_reussite"] == 0.5


def test_effet_avant_est_le_bareme_courant():
    """Une version v1 existe (station repondérée /10) : « avant » la lit, « origine » reste /20."""
    d = donnees([n(1, 500, 101, 201, 15.5, {1: 6.0, 2: 1.0})])
    avant = pj.appliquer([op(pj.REPONDERER, station=101, echelle=10.0)], CRITERES, GRILLES)
    apres = pj.appliquer([op(pj.EXCLURE_STATION, station=101)], CRITERES, GRILLES)
    e = pj.effet(avant, apres, d, GRILLES)
    assert e["origine"]["denominateur"] == 20.0 and e["origine"]["mediane"] == 15.5
    assert e["avant"]["denominateur"] == 10.0 and e["avant"]["mediane"] == 7.75
    # tout exclu : scoring part de totalDelibere = 0 et n'ajoute rien →
    # dénominateur 0, totaux 0 — le jumeau rend la même dégénérescence.
    assert e["apres"]["denominateur"] == 0.0 and e["apres"]["mediane"] == 0.0


def test_memes_operations_insensible_a_l_ordre():
    a = [op(pj.EXCLURE_CRITERE, item=2), op(pj.REPONDERER, station=101, echelle=10.0)]
    assert pj.memes_operations(a, list(reversed(a)))
    assert not pj.memes_operations(a, a[:1])
