"""Concentration d'échec — test binomial bilatéral vs les autres stations (#357)."""

import pytest
from scipy.stats import binomtest

from app.stats.engine import REFUS_AUCUNE_AUTRE_STATION, concentration_echec
from app.stats.types import CONCLUANT, NON_CONCLUANT


def test_station_exactement_dans_la_norme_p_value_1():
    """6 échecs sur 12 quand les autres stations échouent à 50 % : l'observation
    est PILE l'attendu (12 × 0,5 = 6) → p-value bilatérale = 1,0 exactement."""
    i = concentration_echec(echecs_station=6, n_station=12, echecs_autres=10, n_autres=20)
    assert i.statut == CONCLUANT
    assert i.valeur == pytest.approx(0.5)  # le taux de la station
    assert i.details["taux_autres"] == pytest.approx(0.5)
    assert i.details["p_value"] == pytest.approx(1.0)


def test_concentration_extreme_p_value_0():
    """10 échecs sur 10 quand PERSONNE n'échoue ailleurs (0/20) :
    P(X ≥ 10 | p = 0) = 0 → p-value = 0,0. Le signal maximal."""
    i = concentration_echec(echecs_station=10, n_station=10, echecs_autres=0, n_autres=20)
    assert i.statut == CONCLUANT
    assert i.valeur == pytest.approx(1.0)
    assert i.details["p_value"] == pytest.approx(0.0)


def test_croise_contre_scipy():
    """La p-value est celle de ``binomtest(k, n, taux_autres)`` — vérifiable
    à la main sur les fixtures F3 (loi binomiale, somme des queues)."""
    i = concentration_echec(echecs_station=8, n_station=12, echecs_autres=3, n_autres=24)
    assert i.details["p_value"] == pytest.approx(binomtest(8, 12, 3 / 24).pvalue)


def test_effectif_insuffisant():
    i = concentration_echec(echecs_station=5, n_station=9, echecs_autres=1, n_autres=20)
    assert i.statut == NON_CONCLUANT
    assert i.raison == "non concluant — effectif insuffisant (n=9 < 10)"


def test_sans_autre_station_refuse():
    """Une seule station notée (le cas réel de l'examen 77) → rien à comparer."""
    i = concentration_echec(echecs_station=2, n_station=12, echecs_autres=0, n_autres=0)
    assert i.statut == NON_CONCLUANT
    assert i.raison == REFUS_AUCUNE_AUTRE_STATION
