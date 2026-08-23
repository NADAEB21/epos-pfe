"""Harnais des fixtures de vérité F3 (#358) — auto-découverte de
``tests/fixtures/f3/*.json`` (contrat dans le README du dossier).

Il vérifie les FORMULES (``engine.formule_*``) sous les seuils de production —
le jeu F3 fait 8 étudiants, les seuils (leurs propres tests) refuseraient tout.
Une seule implémentation des formules, deux juges : les tests unitaires posés
à la main, et le tableur de F3.

Tant qu'aucune fixture n'est livrée : SKIP avec raison — jamais un faux vert.
"""

import json
from pathlib import Path

import numpy as np
import pytest

from app.stats import engine

_DOSSIER = Path(__file__).parent / "fixtures" / "f3"
_FIXTURES = sorted(_DOSSIER.glob("*.json"))


def _charger(chemin: Path) -> dict:
    return json.loads(chemin.read_text(encoding="utf-8"))


if not _FIXTURES:

    def test_f3_pas_encore_livre():
        pytest.skip("F3 (#358) pas encore livré — le harnais attend tests/fixtures/f3/*.json")


# pytest appelle `ids` même sur une liste vide — la définition paramétrée
# n'existe donc que lorsqu'il y a des fixtures à découvrir.
@pytest.mark.parametrize("chemin", _FIXTURES or [pytest.param(None, marks=pytest.mark.skip)], ids=lambda p: p.stem if p else "vide")
def test_fixture_f3(chemin: Path):
    fx = _charger(chemin)
    tol = fx.get("tolerance", 0.005)
    criteres = {int(c["item_id"]): c for c in fx["criteres"]}
    notations = fx["notations"]

    # Matrice contributions (lignes = étudiants, colonnes = critères, ordre du fichier).
    ordres = [int(c["item_id"]) for c in fx["criteres"]]
    matrice = np.array(
        [
            [
                engine.contribution(
                    criteres[i]["type"], float(n["valeurs"][str(i)]), float(criteres[i]["ponderation"])
                )
                for i in ordres
            ]
            for n in notations
        ],
        dtype=np.float64,
    )
    totaux = matrice.sum(axis=1)

    attendus = fx["attendus"]
    for item_str, attendu in attendus.get("difficulte", {}).items():
        i = int(item_str)
        valeurs = np.array([float(n["valeurs"][item_str]) for n in notations])
        p = engine.formule_difficulte(criteres[i]["type"], criteres[i].get("valeur_max"), valeurs)
        assert p == pytest.approx(attendu, abs=tol), f"difficulté critère {i}"

    for item_str, attendu in attendus.get("discrimination", {}).items():
        col = ordres.index(int(item_str))
        r = engine.formule_pearson(matrice[:, col], totaux - matrice[:, col])
        assert r == pytest.approx(attendu, abs=tol), f"discrimination critère {item_str}"

    if "alpha_cronbach" in attendus:
        a = engine.formule_alpha(matrice)
        assert a == pytest.approx(attendus["alpha_cronbach"], abs=tol), "alpha de Cronbach"
