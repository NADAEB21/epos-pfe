"""Les quatre indices (#357) — formules écrites à la main, testées contre un
calcul manuel (ADR-0029 D1 : « voici la formule, voici notre implémentation
vérifiée au tableur »). numpy n'est qu'un substrat numérique ; aucune formule
n'est un appel de bibliothèque opaque.

FORMULES ET SEUILS (plan IA/BI §5 ; le seuil de concentration est NOTRE choix,
aucun ADR ne le fixait — aligné sur le plancher de la difficulté) :

- Difficulté d'un critère : ``p = moyenne(valeur) / valeur_max``.
  BINAIRE → valeur ∈ {0,1} et ``p = moyenne(valeur)`` (proportion d'acquis) ;
  NUMERIQUE sans ``valeur_max`` → refus « barème sans valeur_max » (jamais un
  nombre inventé). Garde : n ≥ 10 notations verrouillées.
- Discrimination : corrélation de Pearson CORRIGÉE item vs (total − item) —
  c'est la point-bisériale quand l'item est dichotomique. Le « total » est la
  somme des contributions pondérées (même arithmétique que
  ``ExamItemSnapshot.weigh`` : BINAIRE → valeur × ponderation, NUMERIQUE →
  valeur brute), PAS ``score_final`` (qui peut porter un réajustement
  total-niveau, invérifiable au tableur). Garde : n ≥ 15 ; IC bootstrap.
- α de Cronbach : ``α = k/(k−1) × (1 − Σ var_i / var_total)`` sur les
  contributions pondérées, variances ÉCHANTILLON (ddof=1).
  Garde : k ≥ 3 critères ET n ≥ 15 ; IC bootstrap (rééchantillonnage des
  étudiants).
- Concentration d'échec : échec = ``score_final < note_max/2`` (le seuil de
  l'écran A, écrit sur l'écran) ; test binomial BILATÉRAL du taux de la
  station contre le taux poolé des AUTRES stations (scipy ``binomtest`` — le
  test, pas la formule, est délégué : sa p-value est vérifiable à la main sur
  les fixtures). Garde : n ≥ 10 sur la station ET ≥ 1 autre station notée.

Toute l'arithmétique est en float64 (les colonnes des vues sont des REAL).
"""

import math
from collections.abc import Sequence

import numpy as np
from scipy.stats import binomtest

from app.stats import bootstrap
from app.stats.types import CONCLUANT, NON_CONCLUANT, Indice

# ── Seuils (plan §5 ; SEUIL_CONCENTRATION est un choix documenté ici) ─────────
SEUIL_N_DIFFICULTE = 10
SEUIL_N_DISCRIMINATION = 15
SEUIL_N_ALPHA = 15
SEUIL_K_ALPHA = 3
SEUIL_N_CONCENTRATION = 10

# ── Gabarits de refus — texte EXACT, contrat d'interface (ADR-0029 D6) ───────
def _refus_effectif(n: int, seuil: int) -> str:
    return f"non concluant — effectif insuffisant (n={n} < {seuil})"


REFUS_VARIANCE_NULLE = "non concluant — variance nulle (toutes les notes identiques)"
REFUS_VALEUR_MAX_ABSENTE = "non calculable — barème sans valeur_max pour ce critère"
REFUS_AUCUNE_AUTRE_STATION = "non concluant — aucune autre station notée pour comparer"
REFUS_TROP_PEU_CRITERES = "non concluant — seulement {k} critère(s) notable(s) ({seuil} requis)"


def contribution(type_item: str, valeur: float, ponderation: float) -> float:
    """La contribution d'un critère au total — miroir de ``ExamItemSnapshot.weigh``."""
    return valeur * ponderation if type_item == "BINAIRE" else valeur


# ── Les FORMULES nues — partagées entre les fonctions gardées ci-dessous et le
#    harnais F3 (#358), qui vérifie le calcul au tableur SOUS les seuils de
#    production (8 étudiants). Une seule implémentation, deux juges. ──────────


def formule_pearson(x: np.ndarray, y: np.ndarray) -> float:
    """Pearson à la main : cov(x,y) / (sd(x)·sd(y)), ddof=1. NaN si variance nulle."""
    x = np.asarray(x, dtype=np.float64)
    y = np.asarray(y, dtype=np.float64)
    sx = float(np.std(x, ddof=1))
    sy = float(np.std(y, ddof=1))
    if sx == 0.0 or sy == 0.0:
        return float("nan")
    cov = float(np.sum((x - x.mean()) * (y - y.mean())) / (len(x) - 1))
    return cov / (sx * sy)


def formule_alpha(matrice: np.ndarray) -> float:
    """α = k/(k−1) × (1 − Σvar_i/var_total), ddof=1. NaN si var_total nulle."""
    m = np.asarray(matrice, dtype=np.float64)
    var_items = m.var(axis=0, ddof=1)
    var_total = m.sum(axis=1).var(ddof=1)
    if var_total == 0.0:
        return float("nan")
    return (m.shape[1] / (m.shape[1] - 1)) * (1.0 - float(var_items.sum()) / float(var_total))


def formule_difficulte(type_item: str, valeur_max: float | None, valeurs: np.ndarray) -> float:
    """p = moyenne(valeur)/valeur_max (BINAIRE : dénominateur 1). NaN si barème absent."""
    v = np.asarray(valeurs, dtype=np.float64)
    if type_item == "BINAIRE":
        return float(v.mean())
    if valeur_max is None or valeur_max <= 0:
        return float("nan")
    return float(v.mean()) / float(valeur_max)


def difficulte(
    *, type_item: str, valeur_max: float | None, valeurs: Sequence[float]
) -> Indice:
    """p = moyenne(valeur)/valeur_max — la part du barème effectivement obtenue."""
    n = len(valeurs)
    if n < SEUIL_N_DIFFICULTE:
        return Indice(
            code="DIFFICULTE", statut=NON_CONCLUANT, n=n,
            raison=_refus_effectif(n, SEUIL_N_DIFFICULTE),
        )
    v = np.asarray(valeurs, dtype=np.float64)
    p = formule_difficulte(type_item, valeur_max, v)
    if math.isnan(p):
        return Indice(
            code="DIFFICULTE", statut=NON_CONCLUANT, n=n,
            raison=REFUS_VALEUR_MAX_ABSENTE,
        )
    ic = bootstrap.ic_percentile([v], lambda a: formule_difficulte(type_item, valeur_max, a))
    return Indice(code="DIFFICULTE", statut=CONCLUANT, n=n, valeur=p, ic=ic)


def discrimination(
    *, contributions_item: Sequence[float], totaux_sans_item: Sequence[float]
) -> Indice:
    """Point-bisériale corrigée : Pearson(item, total − item), IC bootstrap."""
    n = len(contributions_item)
    if n < SEUIL_N_DISCRIMINATION:
        return Indice(
            code="DISCRIMINATION", statut=NON_CONCLUANT, n=n,
            raison=_refus_effectif(n, SEUIL_N_DISCRIMINATION),
        )
    x = np.asarray(contributions_item, dtype=np.float64)
    y = np.asarray(totaux_sans_item, dtype=np.float64)
    r = formule_pearson(x, y)
    if math.isnan(r):
        return Indice(
            code="DISCRIMINATION", statut=NON_CONCLUANT, n=n,
            raison=REFUS_VARIANCE_NULLE,
        )
    ic = bootstrap.ic_percentile([x, y], formule_pearson)
    return Indice(code="DISCRIMINATION", statut=CONCLUANT, n=n, valeur=float(r), ic=ic)


def alpha_cronbach(*, matrice: Sequence[Sequence[float]]) -> Indice:
    """α = k/(k−1) × (1 − Σvar_i/var_total) — lignes = étudiants, colonnes = critères."""
    m = np.asarray(matrice, dtype=np.float64)
    n = int(m.shape[0]) if m.ndim == 2 else 0
    k = int(m.shape[1]) if m.ndim == 2 else 0
    if k < SEUIL_K_ALPHA:
        return Indice(
            code="ALPHA_CRONBACH", statut=NON_CONCLUANT, n=n,
            raison=REFUS_TROP_PEU_CRITERES.format(k=k, seuil=SEUIL_K_ALPHA),
            details={"k": k},
        )
    if n < SEUIL_N_ALPHA:
        return Indice(
            code="ALPHA_CRONBACH", statut=NON_CONCLUANT, n=n,
            raison=_refus_effectif(n, SEUIL_N_ALPHA), details={"k": k},
        )

    a = formule_alpha(m)
    if math.isnan(a):
        return Indice(
            code="ALPHA_CRONBACH", statut=NON_CONCLUANT, n=n,
            raison=REFUS_VARIANCE_NULLE, details={"k": k},
        )
    # Rééchantillonnage des ÉTUDIANTS (les lignes) — la grille, elle, est fixe.
    ic = bootstrap.ic_percentile([m], formule_alpha)
    return Indice(
        code="ALPHA_CRONBACH", statut=CONCLUANT, n=n,
        valeur=float(a), ic=ic, details={"k": k},
    )


def concentration_echec(
    *, echecs_station: int, n_station: int, echecs_autres: int, n_autres: int
) -> Indice:
    """Taux d'échec de la station testé (binomial bilatéral) contre les autres."""
    if n_station < SEUIL_N_CONCENTRATION:
        return Indice(
            code="CONCENTRATION_ECHEC", statut=NON_CONCLUANT, n=n_station,
            raison=_refus_effectif(n_station, SEUIL_N_CONCENTRATION),
        )
    if n_autres == 0:
        return Indice(
            code="CONCENTRATION_ECHEC", statut=NON_CONCLUANT, n=n_station,
            raison=REFUS_AUCUNE_AUTRE_STATION,
        )
    taux_station = echecs_station / n_station
    taux_autres = echecs_autres / n_autres
    p_value = float(binomtest(echecs_station, n_station, taux_autres).pvalue)
    return Indice(
        code="CONCENTRATION_ECHEC", statut=CONCLUANT, n=n_station,
        valeur=float(taux_station),
        details={
            "taux_autres": float(taux_autres),
            "p_value": p_value,
            "echecs_station": echecs_station,
            "n_autres": n_autres,
        },
    )
