"""Le jumeau Python de ``BaremeDeliberationEngine`` (scoring, #361 / ADR-0030 D4).

Pourquoi un jumeau et pas une re-somme « à notre façon » : l'effet projeté
d'une proposition est montré AVANT la décision (ADR-0021 D10). S'il ne valait
pas, au flottant près, ce que ``GET /notations/examen/{id}/results`` servira
APRÈS l'acceptation, le responsable découvrirait la conséquence après coup —
exactement ce que D10 interdit. Ce module reproduit donc l'arithmétique de
scoring ligne à ligne, y compris ses choix qui ne sont pas les nôtres :

- **DELTA depuis ``score_final``**, jamais une re-somme des items (un
  réajustement ADR-0013 au TOTAL, sans trace item, doit survivre) ;
- **dénominateur depuis ``note_max`` du snapshot de grille** (V9), pas depuis
  la grille vivante ;
- max d'un critère : BINAIRE → ``ponderation`` ; NUMERIQUE → ``valeurMax`` lu
  RÉCURSIVEMENT dans ``items_json`` (feuilles ET parents) ;
- **pas de renormalisation à /20** : la paire brute (score, max) est servie,
  la reconversion est un choix d'écran (#363, ADR-0030 « non décidé ») ;
- **pas d'arrondi** ; deux bornes ``max(0, …)`` seulement ;
- **rétrécissement float32** : ``ExamItemSnapshot.weigh`` calcule en float,
  ``scoreDelibere`` rend un float — le reste est en double. Python est
  float64 partout : on rétrécit explicitement aux mêmes endroits ;
- dénominateurs d'examen sommés sur TOUTES les stations snapshotées, totaux
  sommés sur les notations de L'ÉTUDIANT ; la paire délibérée est ``None``
  tant que la couverture snapshot n'est pas complète (NotationService:122-146).

Testé contre les 17 cas de ``BaremeDeliberationEngineTest`` (valeurs
calculées à la main côté Java) — c'est le juge de paix de l'équivalence.
Fonctions pures : aucune I/O ici.
"""

from __future__ import annotations

import json
import statistics
from collections.abc import Iterable, Mapping
from dataclasses import dataclass, field

import numpy as np

from app.stats.loader import DonneesExamen
from app.stats.types import CritereDef, NotationChargee

TYPE_BINAIRE = "BINAIRE"

# L'énumération FERMÉE de scoring (TypeOperationBareme / CHECK V25) — les noms
# du fil, tels que le client web les POSTe. Aucun autre type, jamais.
EXCLURE_CRITERE = "EXCLURE_CRITERE"
EXCLURE_STATION = "EXCLURE_STATION"
REPONDERER = "REPONDERER"
TYPES_OPERATION = frozenset({EXCLURE_CRITERE, EXCLURE_STATION, REPONDERER})


# ── Formes ───────────────────────────────────────────────────────────────────

@dataclass(frozen=True)
class Operation:
    """Une opération du barème, forme du fil scoring (``OperationRequest``)."""

    type: str
    cible_item_id: int | None = None
    cible_station_id: int | None = None
    nouvelle_echelle: float | None = None

    def as_wire(self) -> dict:
        """Le corps EXACT attendu par POST .../bareme-deliberation (camelCase)."""
        return {
            "type": self.type,
            "cibleItemId": self.cible_item_id,
            "cibleStationId": self.cible_station_id,
            "nouvelleEchelle": self.nouvelle_echelle,
        }

    @staticmethod
    def from_wire(d: Mapping) -> "Operation":
        echelle = d.get("nouvelleEchelle")
        return Operation(
            type=str(d.get("type")),
            cible_item_id=_entier_ou_none(d.get("cibleItemId")),
            cible_station_id=_entier_ou_none(d.get("cibleStationId")),
            nouvelle_echelle=float(echelle) if echelle is not None else None,
        )


def _entier_ou_none(v) -> int | None:
    if v is None or isinstance(v, bool):
        return None
    try:
        return int(v)
    except (TypeError, ValueError):
        return None


@dataclass(frozen=True)
class GrilleSnap:
    """Une ligne de ``exam_grille_snapshot`` (vue V26) — le barème qui a noté."""

    station_id: int
    grille_id: int
    note_max: float
    items_json: str


@dataclass(frozen=True)
class BaremeCourant:
    """La version courante du barème de délibération côté scoring."""

    version: int
    operations: tuple[Operation, ...]


@dataclass(frozen=True)
class OperationItem:
    """Une opération critère RÉSOLUE : le critère, son max, le type, l'échelle."""

    critere: CritereDef
    max: float
    type: str
    nouvelle_echelle: float | None


@dataclass(frozen=True)
class BaremeApplique:
    """Le miroir de ``BaremeDeliberationEngine.BaremeApplique``."""

    stations_exclues: frozenset[int]
    echelle_par_station: dict[int, float]
    operations_par_item: dict[int, OperationItem]
    station_par_grille: dict[int, int]
    max_original_par_station: dict[int, float]
    max_delibere_par_station: dict[int, float]
    operations_ignorees: tuple[int, ...] = field(default_factory=tuple)


# ── Arithmétique élémentaire (ExamItemSnapshot.weigh, maxDeItem, valeurMaxParItem) ──

def float32(x: float) -> float:
    """Rétrécissement explicite au float Java, puis retour en float Python."""
    return float(np.float32(x))


def weigh(critere: CritereDef, valeur: float | None) -> float:
    """``ExamItemSnapshot.weigh`` : ``v * (float) ponderation`` en FLOAT32.

    Java : ``float v = valeur != null ? valeur : 0f; return TYPE_BINAIRE ? v * (float) pond : v;``
    La multiplication se fait en float (32 bits) — on la reproduit telle quelle.
    """
    v = np.float32(valeur if valeur is not None else 0.0)
    if critere.type == TYPE_BINAIRE:
        return float(v * np.float32(critere.ponderation))
    return float(v)


def valeur_max_par_item(items_json: str | None) -> dict[int, float]:
    """``itemId → valeurMax`` depuis ``items_json`` (arbre aplati, feuilles ET
    parents, descente ``sousCriteres``). Une ligne illisible rend ``{}`` sans
    lever — posture #355 : une station corrompue n'éteint pas la délibération.
    Garde MissingNode : on s'arrête sur une clé absente au lieu de boucler.
    """
    out: dict[int, float] = {}
    if not items_json:
        return out
    try:
        racine = json.loads(items_json)
    except (TypeError, ValueError):
        return out
    _collect(racine, out)
    return out


def _collect(node, out: dict[int, float]) -> None:
    if node is None:
        return
    if isinstance(node, list):
        for child in node:
            _collect(child, out)
        return
    if not isinstance(node, dict):
        return
    ident = node.get("id")
    vmax = node.get("valeurMax")
    if _est_nombre(ident) and _est_nombre(vmax):
        out[int(ident)] = float(vmax)
    sous = node.get("sousCriteres")
    if sous is not None:  # clé absente = arrêt (le MissingNode de Jackson)
        _collect(sous, out)


def _est_nombre(v) -> bool:
    return isinstance(v, (int, float)) and not isinstance(v, bool)


def max_de_item(critere: CritereDef | None, vmax_par_item: Mapping[int, float]) -> float | None:
    """``maxDeItem`` : BINAIRE → ponderation ; NUMERIQUE → valeurMax du snapshot ; sinon None."""
    if critere is None:
        return None
    if critere.type == TYPE_BINAIRE:
        return float(critere.ponderation)
    v = vmax_par_item.get(critere.item_id)
    return float(v) if v is not None else None


# ── chargerCourant ───────────────────────────────────────────────────────────

def appliquer(
    operations: Iterable[Operation],
    criteres: Mapping[int, CritereDef],
    grilles: Mapping[int, GrilleSnap],
) -> BaremeApplique:
    """Le miroir de ``chargerCourant`` : résout une liste d'opérations contre
    le snapshot et calcule le dénominateur délibéré par station.

    ``grilles`` : station_id → GrilleSnap. Une opération critère irrésoluble
    (critère inconnu ou max absent) est IGNORÉE et listée dans
    ``operations_ignorees`` — comme le ``log.error … opération ignorée`` de
    scoring ; la validation amont (``valider``) empêche d'en proposer une.
    """
    station_par_grille = {g.grille_id: g.station_id for g in grilles.values()}
    max_original = {g.station_id: float(g.note_max) for g in grilles.values()}
    vmax_par_item: dict[int, float] = {}
    for g in grilles.values():
        vmax_par_item.update(valeur_max_par_item(g.items_json))

    stations_exclues: set[int] = set()
    echelle_par_station: dict[int, float] = {}
    operations_par_item: dict[int, OperationItem] = {}
    ignorees: list[int] = []
    for op in operations:
        if op.type == EXCLURE_STATION:
            if op.cible_station_id is not None:
                stations_exclues.add(op.cible_station_id)
        elif op.cible_station_id is not None:
            if op.nouvelle_echelle is not None:
                echelle_par_station[op.cible_station_id] = float(op.nouvelle_echelle)
        else:
            critere = criteres.get(op.cible_item_id) if op.cible_item_id is not None else None
            mx = max_de_item(critere, vmax_par_item)
            if critere is None or mx is None:
                if op.cible_item_id is not None:
                    ignorees.append(op.cible_item_id)
                continue
            operations_par_item[critere.item_id] = OperationItem(
                critere=critere, max=mx, type=op.type, nouvelle_echelle=op.nouvelle_echelle
            )

    max_delibere: dict[int, float] = {}
    for station_id, base in max_original.items():
        if station_id in stations_exclues:
            continue  # absente = exclue des deux sommes
        if station_id in echelle_par_station:
            max_delibere[station_id] = echelle_par_station[station_id]
            continue
        mx = base
        for op_item in operations_par_item.values():
            if station_par_grille.get(op_item.critere.grille_id) != station_id:
                continue
            if op_item.type == EXCLURE_CRITERE:
                mx -= op_item.max
            else:  # REPONDERER critère
                mx += float(op_item.nouvelle_echelle) - op_item.max
        max_delibere[station_id] = max(0.0, mx)

    return BaremeApplique(
        stations_exclues=frozenset(stations_exclues),
        echelle_par_station=echelle_par_station,
        operations_par_item=operations_par_item,
        station_par_grille=station_par_grille,
        max_original_par_station=max_original,
        max_delibere_par_station=max_delibere,
        operations_ignorees=tuple(ignorees),
    )


# ── scoreDelibere ────────────────────────────────────────────────────────────

def score_delibere(bareme: BaremeApplique, notation: NotationChargee) -> float | None:
    """Le miroir de ``scoreDelibere`` : delta depuis ``score_final``, rendu en
    float32 (le ``(float)`` de retour Java). ``None`` si la station est exclue
    ou si ``score_final`` est NULL."""
    station_id = notation.station_id
    if station_id is not None and station_id in bareme.stations_exclues:
        return None
    if notation.score_final is None:
        return None
    score = float(np.float32(notation.score_final))  # Float Java → double

    for op_item in bareme.operations_par_item.values():
        item_station = bareme.station_par_grille.get(op_item.critere.grille_id)
        if item_station is None or item_station != station_id:
            continue
        valeur = notation.valeurs.get(op_item.critere.item_id)
        contribution = weigh(op_item.critere, valeur)
        if op_item.type == EXCLURE_CRITERE:
            score -= contribution
        else:  # REPONDERER critère : ré-échelonnage proportionnel (ADR-0021 D8)
            score += _rescale(op_item, valeur) - contribution

    echelle = bareme.echelle_par_station.get(station_id)
    if echelle is not None:
        base = bareme.max_original_par_station.get(station_id)
        score = score / base * echelle if (base is not None and base > 0) else 0.0
    return float32(max(0.0, score))


def _rescale(op_item: OperationItem, valeur: float | None) -> float:
    v = float(np.float32(valeur)) if valeur is not None else 0.0  # Float → double
    if op_item.critere.type == TYPE_BINAIRE:
        return v * float(op_item.nouvelle_echelle)
    return v / op_item.max * float(op_item.nouvelle_echelle) if op_item.max > 0 else 0.0


# ── Les deux totaux par étudiant (NotationService.getResultatsByExamen) ──────

@dataclass(frozen=True)
class TotalEtudiant:
    etudiant_id: int
    total_original: float
    total_delibere: float | None


@dataclass(frozen=True)
class Totaux:
    """Ce que /results sert : dénominateurs d'examen + totaux par étudiant."""

    couverture_complete: bool
    denominateur_original: float | None
    denominateur_delibere: float | None
    etudiants: tuple[TotalEtudiant, ...]


def totaux(
    bareme: BaremeApplique | None,
    donnees: DonneesExamen,
    grilles: Mapping[int, GrilleSnap],
) -> Totaux:
    """Reproduit la boucle de ``getResultatsByExamen`` sur les notations
    VERROUILLÉES visibles du module (la vue V20 — /results, lui, voit toutes
    les notations : sur un examen clos par le flux nominal, c'est la même
    population)."""
    max_original = {g.station_id: float(g.note_max) for g in grilles.values()}
    couverture = bool(max_original) and all(
        n.station_id is not None and n.station_id in max_original for n in donnees.notations
    )
    denom_original = sum(max_original.values()) if couverture else None
    denom_delibere = (
        sum(bareme.max_delibere_par_station.values())
        if (couverture and bareme is not None) else None
    )

    par_etudiant: dict[int, list[NotationChargee]] = {}
    for n in donnees.notations:
        if n.etudiant_id is None:
            continue  # notation orpheline — ignorée, comme scoring
        par_etudiant.setdefault(n.etudiant_id, []).append(n)

    lignes = []
    for etudiant_id, rows in par_etudiant.items():
        total = 0.0
        for n in rows:
            if n.score_final is not None:
                total += float(np.float32(n.score_final))
        total_delibere: float | None = 0.0 if (bareme is not None and couverture) else None
        if bareme is not None:
            for n in rows:
                s = score_delibere(bareme, n)
                if s is not None and total_delibere is not None:
                    total_delibere += s
        lignes.append(TotalEtudiant(etudiant_id, total, total_delibere))

    return Totaux(couverture, denom_original, denom_delibere, tuple(lignes))


# ── L'effet projeté (médiane, taux de réussite, dénominateur) ────────────────

def resume(valeurs: list[float], denominateur: float | None) -> dict:
    """Le résumé d'une distribution de totaux — la lecture D10 (« médiane
    11.2 → 12.8, taux de réussite 54 % → 71 % »). Réussite = total ≥ max/2,
    le miroir du seuil d'échec de l'écran A (``score_final < note_max/2``).
    """
    if not valeurs or denominateur is None:
        return {
            "n_etudiants": len(valeurs), "denominateur": denominateur,
            "mediane": None, "moyenne": None, "taux_reussite": None,
        }
    seuil = denominateur / 2
    return {
        "n_etudiants": len(valeurs),
        "denominateur": denominateur,
        "mediane": float(statistics.median(valeurs)),
        "moyenne": float(sum(valeurs) / len(valeurs)),
        "taux_reussite": float(sum(1 for v in valeurs if v >= seuil) / len(valeurs)),
    }


def effet(
    avant: BaremeApplique | None,
    apres: BaremeApplique | None,
    donnees: DonneesExamen,
    grilles: Mapping[int, GrilleSnap],
) -> dict | None:
    """``{origine, avant, apres}`` — trois lectures du même examen :
    au barème d'origine, au barème COURANT (« avant » — l'origine si aucune
    version n'existe), et au barème proposé (« apres »). ``None`` si la
    couverture snapshot est incomplète : scoring ne servirait aucun total
    délibéré, on ne projette pas ce qu'on ne peut pas tenir.
    """
    t_apres = totaux(apres, donnees, grilles)
    if not t_apres.couverture_complete:
        return None
    t_avant = totaux(avant, donnees, grilles)
    origine = resume([e.total_original for e in t_apres.etudiants], t_apres.denominateur_original)

    def lecture(t: Totaux) -> dict:
        if t.denominateur_delibere is None:
            return origine
        return resume(
            [e.total_delibere for e in t.etudiants if e.total_delibere is not None],
            t.denominateur_delibere,
        )

    return {"origine": origine, "avant": lecture(t_avant), "apres": lecture(t_apres)}


# ── Ce que scoring REFUSERAIT à la création (BaremeDeliberationService.validerOperations) ──

@dataclass(frozen=True)
class Refus:
    code: str
    detail: str


def valider(
    operations: list[Operation],
    criteres: Mapping[int, CritereDef],
    items_snapshotes: frozenset[int],
    grilles: Mapping[int, GrilleSnap],
    courant: BaremeCourant | None,
) -> Refus | None:
    """Refuser de PROPOSER ce que scoring refuserait de CRÉER — les mêmes
    contrôles, dans le même ordre, en codes stables (le texte nominatif reste
    celui de scoring au moment du POST). ``items_snapshotes`` : les critères
    présents dans ``exam_item_snapshot`` (= vus dans au moins une notation de
    la vue V20, jointure interne sur le snapshot). ``None`` = acceptable.
    """
    if not operations:
        return None  # version « retour au barème du lancement » (D3)
    if not grilles and not items_snapshotes:
        return Refus("AUCUN_SNAPSHOT", "examen sans barème snapshoté (antérieur à V19)")

    station_par_grille = {g.grille_id: g.station_id for g in grilles.values()}
    vmax_par_item: dict[int, float] = {}
    for g in grilles.values():
        vmax_par_item.update(valeur_max_par_item(g.items_json))

    items_cibles: set[int] = set()
    stations_cibles: set[int] = set()
    stations_des_items: set[int] = set()

    def cible_item(op: Operation) -> Refus | int:
        if op.cible_item_id not in items_snapshotes or op.cible_item_id not in criteres:
            return Refus("CIBLE_ITEM_ABSENTE",
                         f"critère {op.cible_item_id} absent du snapshot de l'examen")
        if max_de_item(criteres[op.cible_item_id], vmax_par_item) is None:
            return Refus("CIBLE_ITEM_SANS_VALEUR_MAX",
                         f"critère {op.cible_item_id} sans valeur maximale au snapshot")
        station = station_par_grille.get(criteres[op.cible_item_id].grille_id)
        if station is None:
            return Refus("GRILLE_NON_SNAPSHOTEE",
                         f"grille {criteres[op.cible_item_id].grille_id} du critère "
                         f"{op.cible_item_id} jamais snapshotée (station jamais notée)")
        if op.cible_item_id in items_cibles:
            return Refus("DOUBLE_CIBLE", f"critère {op.cible_item_id} ciblé deux fois")
        items_cibles.add(op.cible_item_id)
        return station

    def cible_station(op: Operation) -> Refus | None:
        if op.cible_station_id not in station_par_grille.values():
            return Refus("CIBLE_STATION_ABSENTE",
                         f"station {op.cible_station_id} absente du snapshot de l'examen")
        if op.cible_station_id in stations_cibles:
            return Refus("DOUBLE_CIBLE", f"station {op.cible_station_id} ciblée deux fois")
        stations_cibles.add(op.cible_station_id)
        return None

    for op in operations:
        if op.type not in TYPES_OPERATION:
            return Refus("TYPE_INCONNU", f"type d'opération inconnu : {op.type}")
        if op.type == EXCLURE_CRITERE:
            if op.cible_item_id is None or op.cible_station_id is not None:
                return Refus("CIBLE_MAL_FORMEE", "EXCLURE_CRITERE cible un critère seul")
            r = cible_item(op)
            if isinstance(r, Refus):
                return r
            stations_des_items.add(r)
        elif op.type == EXCLURE_STATION:
            if op.cible_station_id is None or op.cible_item_id is not None:
                return Refus("CIBLE_MAL_FORMEE", "EXCLURE_STATION cible une station seule")
            r = cible_station(op)
            if r is not None:
                return r
        else:  # REPONDERER
            if op.nouvelle_echelle is None or op.nouvelle_echelle <= 0:
                return Refus("REPONDERER_SANS_ECHELLE",
                             "REPONDERER exige nouvelleEchelle strictement positive")
            sur_item = op.cible_item_id is not None
            sur_station = op.cible_station_id is not None
            if sur_item == sur_station:
                return Refus("CIBLE_MAL_FORMEE",
                             "REPONDERER cible SOIT un critère SOIT une station, exactement un")
            if sur_item:
                r = cible_item(op)
                if isinstance(r, Refus):
                    return r
                stations_des_items.add(r)
            else:
                r = cible_station(op)
                if r is not None:
                    return r
        if op.type != REPONDERER and op.nouvelle_echelle is not None:
            return Refus("ECHELLE_HORS_REPONDERER",
                         "nouvelleEchelle n'a de sens que pour REPONDERER")

    for station_id in stations_cibles:
        if station_id in stations_des_items:
            return Refus("NIVEAUX_MELANGES",
                         f"station {station_id} ciblée en même temps que ses critères")

    if courant is not None and memes_operations(courant.operations, operations):
        return Refus("IDENTIQUE_VERSION_COURANTE",
                     f"opérations identiques à la version courante v{courant.version}")
    return None


def memes_operations(a: Iterable[Operation], b: Iterable[Operation]) -> bool:
    """Égalité d'ensembles d'opérations (l'ordre n'est pas signifiant) — le
    contrôle de double application (409) de scoring, ADR-0030 D5."""
    def cle(op: Operation):
        return (op.type, op.cible_item_id, op.cible_station_id,
                None if op.nouvelle_echelle is None else float(op.nouvelle_echelle))
    return sorted(map(cle, a), key=repr) == sorted(map(cle, b), key=repr)
