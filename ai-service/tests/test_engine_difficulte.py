"""Difficulté p = moyenne(valeur)/valeur_max — exemples posés à la main (#357)."""

import pytest

from app.stats.engine import REFUS_VALEUR_MAX_ABSENTE, difficulte
from app.stats.types import CONCLUANT, NON_CONCLUANT


def test_numerique_calcul_manuel():
    """valeurs = [2,3,5,4,1,0,3,2,4,5] (n=10), somme = 29, moyenne = 2,9 ;
    valeur_max = 5 → p = 2,9 / 5 = 0,58. Vérifiable au tableur en une ligne."""
    i = difficulte(type_item="NUMERIQUE", valeur_max=5.0, valeurs=[2, 3, 5, 4, 1, 0, 3, 2, 4, 5])
    assert i.statut == CONCLUANT
    assert i.n == 10
    assert i.valeur == pytest.approx(0.58)


def test_binaire_est_une_proportion():
    """BINAIRE : valeur ∈ {0,1}, 7 acquis sur 10 → p = 0,7 (aucun valeur_max requis)."""
    i = difficulte(type_item="BINAIRE", valeur_max=None, valeurs=[1, 1, 0, 1, 1, 0, 1, 1, 0, 1])
    assert i.statut == CONCLUANT
    assert i.valeur == pytest.approx(0.7)


def test_ic_bootstrap_encadre_p_et_est_deterministe():
    """L'IC percentile contient p, et deux appels identiques rendent le MÊME IC
    (graine fixe — reproductibilité ADR-0029 D3)."""
    valeurs = [2, 3, 5, 4, 1, 0, 3, 2, 4, 5]
    a = difficulte(type_item="NUMERIQUE", valeur_max=5.0, valeurs=valeurs)
    b = difficulte(type_item="NUMERIQUE", valeur_max=5.0, valeurs=valeurs)
    assert a.ic is not None
    assert a.ic[0] <= a.valeur <= a.ic[1]
    assert a.ic == b.ic


def test_numerique_sans_valeur_max_refuse_nominativement():
    """Le piège réel du barème (exam 77 : valeur_max NULL sur les binaires ET
    d'éventuels numériques mal saisis) : JAMAIS un nombre inventé."""
    i = difficulte(type_item="NUMERIQUE", valeur_max=None, valeurs=[1] * 10)
    assert i.statut == NON_CONCLUANT
    assert i.raison == REFUS_VALEUR_MAX_ABSENTE
    assert i.valeur is None


def test_effectif_insuffisant_au_seuil_moins_un():
    """n = 9 < 10 → refus motivé, chiffres dans le texte."""
    i = difficulte(type_item="BINAIRE", valeur_max=None, valeurs=[1] * 9)
    assert i.statut == NON_CONCLUANT
    assert i.raison == "non concluant — effectif insuffisant (n=9 < 10)"


def test_effectif_au_seuil_passe():
    """n = 10 exactement → concluant (le seuil est inclusif)."""
    i = difficulte(type_item="BINAIRE", valeur_max=None, valeurs=[1, 0] * 5)
    assert i.statut == CONCLUANT
    assert i.valeur == pytest.approx(0.5)
