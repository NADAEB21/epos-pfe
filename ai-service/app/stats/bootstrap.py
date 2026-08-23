"""IC bootstrap percentile, graine FIXE — reproductibilité avant tout (ADR-0029 D3).

Même examen, mêmes entrées → même IC : la graine est une constante, pas l'horloge.
B = 2000 rééchantillonnages, percentiles 2,5 / 97,5 (niveau 95 %).

Un rééchantillon peut rendre la statistique incalculable (variance nulle après
tirage, par exemple) : ces tirages sont écartés. Si moins de ``_MIN_VALIDES``
tirages survivent, l'IC est ``None`` — on ne fabrique pas un intervalle sur
trois points (leçon du contrat de refus, appliquée à l'incertitude elle-même).
"""

import math
from collections.abc import Callable, Sequence

import numpy as np

GRAINE = 42
B = 2000
_MIN_VALIDES = 200


def ic_percentile(
    colonnes: Sequence[np.ndarray],
    statistique: Callable[..., float],
    *,
    b: int = B,
    graine: int = GRAINE,
) -> tuple[float, float] | None:
    """IC 95 % percentile de ``statistique(*colonnes_reechantillonnees)``.

    Les colonnes sont rééchantillonnées ENSEMBLE (mêmes indices tirés — les
    paires item/total restent des paires). ``statistique`` peut rendre NaN sur
    un tirage dégénéré ; ces tirages sont écartés.
    """
    n = len(colonnes[0])
    if n == 0:
        return None
    rng = np.random.default_rng(graine)
    valeurs = []
    for _ in range(b):
        idx = rng.integers(0, n, size=n)
        v = statistique(*(c[idx] for c in colonnes))
        if v is not None and not math.isnan(v):
            valeurs.append(v)
    if len(valeurs) < _MIN_VALIDES:
        return None
    lo, hi = np.percentile(valeurs, [2.5, 97.5])
    return (float(lo), float(hi))
