"""Le runner de bout en bout — données injectées, aucune DB (#357).

Jeu : 2 stations. Station 101 (grille 10, 3 critères) : 16 notations à détail
COMPLET + 1 à détail partiel (écartée de la matrice, comptée). Station 102
(grille 20) : 12 notations à score bas — la concentration d'échec doit s'y lire.
"""

import pytest

from app.stats.loader import DonneesExamen
from app.stats.runner import calculer_indices
from app.stats.types import CritereDef, Exclusions, NotationChargee

_CRITERES = {
    1: CritereDef(1, "Geste A", "BINAIRE", 5.0, None, 10, 101, 20.0),
    2: CritereDef(2, "Calcul B", "NUMERIQUE", 10.0, 10.0, 10, 101, 20.0),
    3: CritereDef(3, "Geste C", "BINAIRE", 5.0, None, 10, 101, 20.0),
    4: CritereDef(4, "Unique D", "NUMERIQUE", 20.0, 20.0, 20, 102, 20.0),
}


def _notation(nid, station, grille, valeurs, score):
    return NotationChargee(
        notation_id=nid, station_id=station, grille_id=grille,
        etudiant_id=nid, saisi_par=67, score_final=score, valeurs=valeurs,
    )


def _donnees():
    notations = []
    # Station 101 : 16 complètes — Geste A acquis pour 12/16 (p = 0,75),
    # scores tous >= 10 (aucun échec sur 20).
    for j in range(16):
        acquis = 1.0 if j < 12 else 0.0
        notations.append(_notation(
            100 + j, 101, 10,
            {1: acquis, 2: 6.0 + (j % 5), 3: 1.0},
            13.0 + (j % 5),
        ))
    # 1 partielle : valeur manquante sur le critère 3 → hors matrice, comptée.
    notations.append(_notation(120, 101, 10, {1: 1.0, 2: 7.0, 3: None}, 12.0))
    # Station 102 : 12 notations, 9 sous 10/20 → concentration d'échec.
    for j in range(12):
        score = 6.0 if j < 9 else 15.0
        notations.append(_notation(200 + j, 102, 20, {4: score}, score))
    return DonneesExamen(
        criteres=_CRITERES, notations=notations,
        exclusions=Exclusions(saisi_par_null=0, detail_incomplet=0,
                              notations_analysees=len(notations)),
    )


def test_structure_et_valeurs_cles():
    r = calculer_indices(77, donnees=_donnees())
    assert r["examen_id"] == 77
    assert {c["item_id"] for c in r["par_critere"]} == {1, 2, 3, 4}

    geste_a = next(c for c in r["par_critere"] if c["item_id"] == 1)
    # Difficulté du Geste A : 12 acquis + 1 (la partielle l'a aussi) sur 17
    # valeurs présentes → p = 13/17.
    assert geste_a["difficulte"]["statut"] == "CONCLUANT"
    assert geste_a["difficulte"]["n"] == 17
    assert geste_a["difficulte"]["valeur"] == pytest.approx(13 / 17)
    # Discrimination : 16 complètes (>= 15) → calculée ou refus variance — jamais absente.
    assert geste_a["discrimination"]["n"] == 16


def test_partielle_ecartee_de_la_matrice_et_comptee():
    r = calculer_indices(77, donnees=_donnees())
    assert r["exclusions"]["detail_incomplet"] == 1
    alpha_g10 = next(g for g in r["par_grille"] if g["grille_id"] == 10)
    assert alpha_g10["alpha_cronbach"]["n"] == 16  # 17 − 1 partielle


def test_concentration_station_102():
    """Station 102 : 9 échecs / 12 (taux 0,75) contre 0 / 17 ailleurs
    (les 16 complètes ET la partielle de 101 ont toutes >= 10)."""
    r = calculer_indices(77, donnees=_donnees())
    conc = next(s for s in r["par_station"] if s["station_id"] == 102)["concentration_echec"]
    assert conc["statut"] == "CONCLUANT"
    assert conc["valeur"] == pytest.approx(9 / 12)
    assert conc["details"]["taux_autres"] == pytest.approx(0.0)
    assert conc["details"]["p_value"] == pytest.approx(0.0)


def test_alpha_grille_mono_critere_refuse():
    """La grille 20 n'a qu'un critère → α refuse (k=1 < 3), il ne plante pas."""
    r = calculer_indices(77, donnees=_donnees())
    alpha_g20 = next(g for g in r["par_grille"] if g["grille_id"] == 20)
    assert alpha_g20["alpha_cronbach"]["statut"] == "NON_CONCLUANT"
    assert alpha_g20["alpha_cronbach"]["raison"] == "non concluant — seulement 1 critère(s) notable(s) (3 requis)"


def test_reproductibilite_bout_en_bout():
    """Mêmes entrées → même résultat, IC compris (ADR-0029 D3 avant le cache)."""
    assert calculer_indices(77, donnees=_donnees()) == calculer_indices(77, donnees=_donnees())
