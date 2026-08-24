"""Harnais HTTP des endpoints (#359) — premier TestClient du dépôt.

Convention inchangée : aucun mock framework, aucune DB — les lectures du plan
de données et le cache sont remplacés par des callables/objets en mémoire via
monkeypatch. Les fixtures construisent un petit examen synthétique (2 feuilles,
notations à détail complet) directement en dataclasses, comme
``test_stats_runner``.
"""

import pytest
from fastapi.testclient import TestClient

from app.main import app
from app.stats.loader import DonneesExamen
from app.stats.types import CritereDef, Exclusions, NotationChargee

EXAMEN_CLOS = 77       # matière 1, TERMINE
EXAMEN_EN_COURS = 42   # matière 1, EN_COURS
EXAMEN_ARCHIVE = 55    # matière 1, ARCHIVE
EXAMEN_HORS_MATIERE = 63  # matière 4


def _criteres() -> dict[int, CritereDef]:
    return {
        1: CritereDef(item_id=1, libelle="Pesée", type="NUMERIQUE", ponderation=10.0,
                      valeur_max=10.0, grille_id=5, station_id=9, note_max=20.0),
        2: CritereDef(item_id=2, libelle="EPI", type="BINAIRE", ponderation=10.0,
                      valeur_max=None, grille_id=5, station_id=9, note_max=20.0),
    }


def _notation(nid: int, saisi_par: int | None, v1: float, v2: float) -> NotationChargee:
    return NotationChargee(
        notation_id=nid, station_id=9, grille_id=5, etudiant_id=100 + nid,
        saisi_par=saisi_par, score_final=v1 + v2 * 10.0,
        valeurs={1: v1, 2: v2},
    )


def _donnees() -> DonneesExamen:
    notations = [
        _notation(1, 67, 6.0, 1.0),
        _notation(2, 67, 4.0, 0.0),
        _notation(3, None, 8.0, 1.0),  # pré-V15 : hors analyses évaluateur
    ]
    return DonneesExamen(
        criteres=_criteres(),
        notations=notations,
        exclusions=Exclusions(saisi_par_null=1, detail_incomplet=0,
                              notations_analysees=len(notations)),
    )


class FauxCache:
    """Le cache ai_db en mémoire — lit/écrit comme ``app.cache``, sans DB."""

    def __init__(self):
        self.lignes: dict[tuple[int, str], dict] = {}
        self.lectures = 0
        self.ecritures = 0

    def lire(self, examen_id, entrees_hash):
        self.lectures += 1
        return self.lignes.get((examen_id, entrees_hash))

    def ecrire(self, examen_id, entrees_hash, moteur_version, payload):
        self.ecritures += 1
        self.lignes[(examen_id, entrees_hash)] = payload


@pytest.fixture
def faux_cache(monkeypatch):
    fc = FauxCache()
    monkeypatch.setattr("app.cache.lire", fc.lire)
    monkeypatch.setattr("app.cache.ecrire", fc.ecrire)
    return fc


@pytest.fixture
def client(monkeypatch, faux_cache):
    """TestClient avec plan de données synthétique : examens 77/42/55 (matière 1),
    63 (matière 4) — statuts TERMINE / EN_COURS / ARCHIVE / TERMINE."""
    matieres = {EXAMEN_CLOS: 1, EXAMEN_EN_COURS: 1, EXAMEN_ARCHIVE: 1,
                EXAMEN_HORS_MATIERE: 4}
    statuts = {EXAMEN_CLOS: "TERMINE", EXAMEN_EN_COURS: "EN_COURS",
               EXAMEN_ARCHIVE: "ARCHIVE", EXAMEN_HORS_MATIERE: "TERMINE"}
    monkeypatch.setattr("app.db.resolve_matiere", lambda eid: matieres.get(eid))
    monkeypatch.setattr("app.db.statut_examen", lambda eid: statuts.get(eid))
    # 4 verrouillées en base pour 3 visibles de la vue → 1 « sans_aucun_item ».
    monkeypatch.setattr("app.db.nb_notations_verrouillees", lambda eid: 4)
    monkeypatch.setattr("app.stats.loader.charger_examen",
                        lambda eid, **_kw: _donnees())
    return TestClient(app)


RESP_M1 = {"X-User-Authorities": "ROLE_RESPONSABLE_MATIERE:1"}
RESP_M2 = {"X-User-Authorities": "ROLE_RESPONSABLE_MATIERE:2"}
EVAL = {"X-User-Authorities": "ROLE_EVALUATEUR"}
ADMIN = {"X-User-Authorities": "ROLE_SUPER_ADMIN"}
