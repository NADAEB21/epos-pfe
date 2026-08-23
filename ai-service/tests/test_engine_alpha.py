"""α de Cronbach = k/(k−1) × (1 − Σvar_i/var_total), ddof=1 — à la main (#357)."""

import pytest

from app.stats.engine import REFUS_VARIANCE_NULLE, alpha_cronbach
from app.stats.types import CONCLUANT, NON_CONCLUANT

# 15 étudiants, motif A = [0×5, 1×5, 2×5] : moyenne 1, SS = 5·1+5·0+5·1 = 10,
# var (ddof=1) = 10/14 = 5/7. Ces chiffres se recalculent au tableur en une colonne.
_A = [0.0] * 5 + [1.0] * 5 + [2.0] * 5


def test_items_parfaitement_correles_alpha_egale_1():
    """3 items IDENTIQUES (A, A, A) : var_i = 5/7 chacun → Σ = 15/7 ;
    total = 3A → var_total = 9 × 5/7 = 45/7 ;
    α = (3/2) × (1 − (15/7)/(45/7)) = 1,5 × (1 − 1/3) = 1,0 exactement."""
    matrice = [[a, a, a] for a in _A]
    i = alpha_cronbach(matrice=matrice)
    assert i.statut == CONCLUANT
    assert i.n == 15
    assert i.details["k"] == 3
    assert i.valeur == pytest.approx(1.0)


def test_item_contraire_alpha_negatif_calcul_manuel():
    """Items (A, A, 2−A) : var_i = 5/7 chacun → Σ = 15/7 ;
    total = A + A + (2−A) = A + 2 → var_total = var(A) = 5/7 ;
    α = (3/2) × (1 − (15/7)/(5/7)) = 1,5 × (1 − 3) = −3,0 exactement.
    (Un α négatif est mathématiquement légal — il crie que la grille est
    incohérente ; le moteur le rend tel quel, l'interprétation est à N6.)"""
    matrice = [[a, a, 2.0 - a] for a in _A]
    i = alpha_cronbach(matrice=matrice)
    assert i.statut == CONCLUANT
    assert i.valeur == pytest.approx(-3.0)


def test_ic_bootstrap_deterministe():
    matrice = [[a, a + (j % 3) * 0.5, 2.0 - a] for j, a in enumerate(_A)]
    a1 = alpha_cronbach(matrice=matrice)
    a2 = alpha_cronbach(matrice=matrice)
    assert a1.ic is not None and a1.ic == a2.ic


def test_moins_de_3_criteres_refuse():
    matrice = [[a, a] for a in _A]
    i = alpha_cronbach(matrice=matrice)
    assert i.statut == NON_CONCLUANT
    assert i.raison == "non concluant — seulement 2 critère(s) notable(s) (3 requis)"


def test_effectif_insuffisant():
    """n = 14 < 15 → refus motivé (le k, lui, est bon)."""
    matrice = [[a, a, a] for a in _A[:14]]
    i = alpha_cronbach(matrice=matrice)
    assert i.statut == NON_CONCLUANT
    assert i.raison == "non concluant — effectif insuffisant (n=14 < 15)"


def test_variance_totale_nulle_refuse():
    """Toutes les lignes identiques → var_total = 0 → refus, pas une division par zéro."""
    matrice = [[1.0, 2.0, 3.0]] * 15
    i = alpha_cronbach(matrice=matrice)
    assert i.statut == NON_CONCLUANT
    assert i.raison == REFUS_VARIANCE_NULLE
