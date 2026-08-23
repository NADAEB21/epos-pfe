"""Discrimination — point-bisériale corrigée = Pearson(item, total − item) (#357).

L'implémentation est écrite à la main (cov/σσ, ddof=1) : on la vérifie sur des
cas dont le résultat se déduit sans machine, PUIS on la croise contre
``scipy.stats.pearsonr`` sur un jeu quelconque — deux preuves indépendantes.
"""

import pytest
from scipy.stats import pearsonr

from app.stats.engine import REFUS_VARIANCE_NULLE, discrimination
from app.stats.types import CONCLUANT, NON_CONCLUANT


def test_relation_lineaire_parfaite_r_egale_1():
    """total−item = 2×item + 3 pour 16 notations → r = 1 exactement (aucun
    calcul requis : une relation affine croissante a une corrélation de 1)."""
    item = list(range(16))
    reste = [2 * x + 3 for x in item]
    i = discrimination(contributions_item=item, totaux_sans_item=reste)
    assert i.statut == CONCLUANT
    assert i.n == 16
    assert i.valeur == pytest.approx(1.0)


def test_relation_inverse_r_egale_moins_1():
    """Un critère qui pénalise les meilleurs : reste = −item → r = −1."""
    item = list(range(16))
    reste = [-x for x in item]
    i = discrimination(contributions_item=item, totaux_sans_item=reste)
    assert i.valeur == pytest.approx(-1.0)


def test_croise_contre_scipy_sur_un_jeu_quelconque():
    """Même valeur que ``scipy.stats.pearsonr`` (référence indépendante)."""
    item = [1, 0, 1, 1, 0, 1, 0, 1, 1, 1, 0, 0, 1, 1, 0, 1]
    reste = [12, 8, 14, 11, 9, 15, 7, 13, 12, 16, 6, 9, 14, 12, 8, 15]
    i = discrimination(contributions_item=item, totaux_sans_item=reste)
    assert i.statut == CONCLUANT
    assert i.valeur == pytest.approx(pearsonr(item, reste).statistic)


def test_ic_bootstrap_present_et_deterministe():
    item = [1, 0, 1, 1, 0, 1, 0, 1, 1, 1, 0, 0, 1, 1, 0, 1]
    reste = [12, 8, 14, 11, 9, 15, 7, 13, 12, 16, 6, 9, 14, 12, 8, 15]
    a = discrimination(contributions_item=item, totaux_sans_item=reste)
    b = discrimination(contributions_item=item, totaux_sans_item=reste)
    assert a.ic is not None and a.ic == b.ic
    assert a.ic[0] <= a.valeur <= a.ic[1]


def test_item_sans_variance_refuse():
    """Tout le monde a la même note à l'item → r indéfini → refus, pas un NaN."""
    i = discrimination(contributions_item=[3.0] * 16, totaux_sans_item=list(range(16)))
    assert i.statut == NON_CONCLUANT
    assert i.raison == REFUS_VARIANCE_NULLE


def test_effectif_insuffisant():
    """n = 14 < 15 → refus motivé."""
    i = discrimination(contributions_item=list(range(14)), totaux_sans_item=list(range(14)))
    assert i.statut == NON_CONCLUANT
    assert i.raison == "non concluant — effectif insuffisant (n=14 < 15)"
