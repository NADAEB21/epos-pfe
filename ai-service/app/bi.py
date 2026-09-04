"""BI — la face transversale des trois étages (#365 / N10).

Le plan IA/BI (§2, §4) et ADR-0029 D5 le disent : le BI n'est pas un
quatrième étage, ce sont LES MÊMES agrégats (totaux, taux de réussite,
distribution, échec par station) servis à une autre échelle — la matière dans
le temps, la faculté toutes matières confondues. Aucune arithmétique nouvelle :
``projection.resume`` (réussite = total ≥ dénominateur/2, le miroir du seuil
d'échec de l'écran A), ``projection.totaux`` (les deux totaux par étudiant,
jumeau de scoring) et la règle d'échec par station du runner
(``score_final < note_max/2``).

Périmètre (ADR-0021 D5, ADR-0029 D4) :
- ``tendances_matiere`` : la matière du responsable — les sessions CLOSES dans
  l'ordre des dates, chacune avec sa distribution et ses stations ;
- ``synthese_faculte`` : SUPER_ADMIN, **agrégé d'abord, jamais par étudiant** —
  les totaux par étudiant sont consommés ICI (médiane, taux) et ne sortent
  jamais de ce module. Un BI sans périmètre serait « la plus grande fuite de
  données de la plateforme » (D5, littéral) : l'invariant est structurel
  (aucun ``etudiant_id`` dans la réponse — verrouillé par test).

Vérité de périmètre : un examen n'entre dans une matière que si le SNAPSHOT
scoring (``v_ai_exam_matiere``, la vérité que scoring applique à ses propres
écritures, guard.py) le dit — la matière VIVANTE d'exam_db (``v_ai_examens``)
sert à lister les candidats et à porter la date, jamais à décider du périmètre.
Un examen dont la matière vivante a dérivé du snapshot est écarté ET compté.

Pas de cache ai_db ici : ADR-0029 D3 (« jamais de re-calcul par requête sur le
chemin chaud ») vise le calcul d'un examen pendant la délibération ; ces
lectures sont froides, bornées par les sessions closes d'une matière, et
chaque carte porte son ``entrees_hash`` — la reproductibilité est conservée.

Contrat de refus (ADR-0029 D6) : ce qui ne peut pas être lu est DIT par un
code fermé + une raison en français, jamais omis en silence.

#401 (ADR-0030 D4 révisé, décision Nada 2026-09-04) : la lecture DÉLIBÉRÉE est
le résultat dès qu'une version de barème existe et que scoring la sert ; chaque
carte porte ``lecture`` (l'effective), ``lecture_officielle`` (DELIBERE|ORIGINE)
et garde ``origine`` comme trace. Bins, pooling de la synthèse et échec par
station suivent la lecture effective ; une station exclue est listée à part.
"""

import statistics
from collections.abc import Sequence
from datetime import date, datetime

from app import db
from app.bareme import projection as pj
from app.bareme import propositions as props
from app.stats import engine
from app.stats import hash as stats_hash
from app.stats import loader, runner

# ── Codes de lecture — l'union FERMÉE que le client web (lecture-bi.ts) traduit ──
SANS_NOTATION = "SANS_NOTATION"
COUVERTURE_INCOMPLETE = "COUVERTURE_INCOMPLETE"
AUCUN_EXAMEN_CLOS = "AUCUN_EXAMEN_CLOS"
EFFECTIF_INSUFFISANT = "EFFECTIF_INSUFFISANT"

RAISON_SANS_NOTATION = "aucune notation verrouillée — rien à lire pour cette session"
RAISON_COUVERTURE_INCOMPLETE = (
    "une station notée n'a pas de barème enregistré au moment de l'épreuve — "
    "totaux et taux de réussite non calculables"
)
RAISON_AUCUN_EXAMEN_CLOS = (
    "aucune session close pour cette matière — les tendances n'existent qu'à l'usage"
)

NB_BINS = 5          # même découpage que l'écran A (resultats.component.ts, DELIBERATION_BINS)
SEUIL_N_SYNTHESE = engine.SEUIL_N_DIFFICULTE   # 10 — le plancher le plus bas du moteur
SUR_20 = 20.0


# ── Distribution ─────────────────────────────────────────────────────────────

def _fmt(v: float) -> str:
    return str(int(v)) if float(v).is_integer() else f"{v:.1f}"


def bins(valeurs: Sequence[float], maximum: float | None, seuil: float) -> list[dict]:
    """Histogramme à ``NB_BINS`` classes de largeur égale sur [0, maximum] —
    la valeur ``maximum`` tombe dans la DERNIÈRE classe (bord droit), comme
    ``binsFor`` de l'écran A. ``sousSeuil`` marque les classes entièrement sous
    le seuil de réussite (le client les colore). ``maximum`` ≤ 0 → aucune
    classe : on ne fabrique pas d'axe."""
    if maximum is None or maximum <= 0:
        return []
    largeur = maximum / NB_BINS
    comptes = [0] * NB_BINS
    for v in valeurs:
        idx = min(NB_BINS - 1, max(0, int(v // largeur)))
        comptes[idx] += 1
    n = len(valeurs)
    return [
        {
            "label": f"{_fmt(i * largeur)}–{_fmt((i + 1) * largeur)}",
            "count": c,
            "pct": (c / n * 100.0) if n else 0.0,
            "sousSeuil": (i + 1) * largeur <= seuil,
        }
        for i, c in enumerate(comptes)
    ]


# ── La carte d'UNE session close ─────────────────────────────────────────────

def _iso(d) -> str | None:
    if d is None:
        return None
    if isinstance(d, (date, datetime)):
        return d.isoformat()
    return str(d)


def _par_station(donnees, grilles: dict, applique=None) -> tuple[list[dict], list[int]]:
    """Échec par station : ``score < max/2`` (règle du runner), sous le barème
    EFFECTIF (#401) : avec un barème délibéré appliqué, le score et le max sont
    ceux de ``BaremeDeliberationEngine`` (jumeau ``projection``) et une station
    EXCLUE sort du tableau — listée à part, jamais tue. Sans barème, le
    ``note_max`` vient du SNAPSHOT de grille quand la station en a un, sinon de
    la grille vivante (comme les indices)."""
    note_max = {c.station_id: c.note_max for c in donnees.criteres.values()}
    note_max.update({sid: g.note_max for sid, g in grilles.items()})
    if applique is not None:
        note_max = dict(applique.max_delibere_par_station)
    scores: dict[int, list[float]] = {}
    exclues: set[int] = set()
    for n in donnees.notations:
        if n.score_final is None:
            continue
        if applique is not None and n.station_id in applique.stations_exclues:
            exclues.add(n.station_id)
            continue
        if n.station_id not in note_max:
            continue
        score = pj.score_delibere(applique, n) if applique is not None else float(n.score_final)
        if score is None:
            continue
        scores.setdefault(n.station_id, []).append(float(score))
    lignes = []
    for station_id, valeurs in sorted(scores.items()):
        nm = float(note_max[station_id])
        echecs = sum(1 for v in valeurs if v < nm / 2)
        lignes.append({
            "station_id": station_id,
            "n": len(valeurs),
            "echecs": echecs,
            "taux_echec": echecs / len(valeurs),
            "mediane": float(statistics.median(valeurs)),
            "note_max": nm,
        })
    return lignes, sorted(exclues)


def _carte(examen_id: int, nom: str | None, date_examen, statut: str) -> tuple[dict, list[float]]:
    """La carte publique d'une session + (privé) ses totaux ramenés /20 pour
    les agrégats de la synthèse. Les totaux ne sortent JAMAIS de ce module."""
    donnees = loader.charger_examen(examen_id)
    total_verrouillees = db.nb_notations_verrouillees(examen_id)
    empreinte = stats_hash.empreinte(donnees, total_verrouillees)
    grilles = props.grilles_depuis_lignes(db.grilles_snapshot(examen_id))
    courant = props.bareme_depuis_lignes(db.bareme_courant(examen_id))
    applique = (
        pj.appliquer(list(courant.operations), donnees.criteres, grilles)
        if courant is not None else None
    )
    t = pj.totaux(applique, donnees, grilles)

    originaux = [e.total_original for e in t.etudiants]
    origine = pj.resume(originaux, t.denominateur_original)
    delibere = None
    if courant is not None and t.denominateur_delibere is not None:
        delibere = pj.resume(
            [e.total_delibere for e in t.etudiants if e.total_delibere is not None],
            t.denominateur_delibere,
        )

    # #401 (ADR-0030 D4 révisé) — la lecture qui FAIT le résultat : délibérée
    # dès qu'une version existe ET que scoring la sert (couverture complète),
    # sinon l'origine. Bins, sur20 (synthèse) et échec par station la suivent ;
    # ``origine`` reste servie comme trace.
    delibere_servi = delibere is not None
    lecture_officielle = "DELIBERE" if delibere_servi else "ORIGINE"
    if delibere_servi:
        effectifs = [e.total_delibere for e in t.etudiants if e.total_delibere is not None]
        denom = t.denominateur_delibere
        lecture = delibere
    else:
        effectifs = originaux
        denom = t.denominateur_original
        lecture = origine
    sur20 = [v * SUR_20 / denom for v in effectifs] if denom else []
    par_station, stations_exclues = _par_station(
        donnees, grilles, applique if delibere_servi else None
    )

    feuilles = runner._feuilles_par_grille(donnees.criteres)
    detail_incomplet = sum(
        1 for n in donnees.notations
        if feuilles.get(n.grille_id) and not runner._detail_complet(n, feuilles[n.grille_id])
    )
    lectures = []
    if not donnees.notations:
        lectures.append({"code": SANS_NOTATION, "raison": RAISON_SANS_NOTATION})
    elif not t.couverture_complete:
        lectures.append({"code": COUVERTURE_INCOMPLETE, "raison": RAISON_COUVERTURE_INCOMPLETE})

    carte = {
        "examen_id": examen_id,
        "nom": nom,
        "date_examen": _iso(date_examen),
        "statut": statut,
        "entrees_hash": empreinte,
        "moteur_version": stats_hash.MOTEUR_VERSION,
        "n_notations_verrouillees": total_verrouillees,
        "couverture_snapshot_complete": t.couverture_complete,
        "origine": origine,
        "delibere": delibere,
        "lecture": lecture,
        "lecture_officielle": lecture_officielle,
        "bareme_version": courant.version if courant is not None else None,
        "bins": bins(effectifs, denom, denom / 2) if denom else [],
        "par_station": par_station,
        "stations_exclues": stations_exclues,
        "exclusions": {
            "saisi_par_null": donnees.exclusions.saisi_par_null,
            "detail_incomplet": detail_incomplet,
            "notations_analysees": donnees.exclusions.notations_analysees,
            "sans_aucun_item": max(0, total_verrouillees - donnees.exclusions.notations_analysees),
        },
        "lectures": lectures,
    }
    return carte, sur20


def resume_examen(examen_id: int, nom: str | None = None, date_examen=None,
                  statut: str = "TERMINE") -> dict:
    """La carte BI d'une session close (publique — sans les totaux)."""
    carte, _ = _carte(examen_id, nom, date_examen, statut)
    return carte


# ── Tendances d'une matière ──────────────────────────────────────────────────

def _trier(lignes: list[dict]) -> list[dict]:
    return sorted(lignes, key=lambda c: (c["date_examen"] or "", c["examen_id"]))


def tendances_matiere(matiere_id: int) -> dict:
    """Les sessions CLOSES de la matière, dans l'ordre des dates — chacune avec
    distribution, stations, barème délibéré éventuel. Les autres sessions sont
    COMPTÉES (non closes, snapshot absent, matière vivante ≠ snapshot), jamais
    tues. Vide → lecture ``AUCUN_EXAMEN_CLOS`` (200, jamais 404 : ai-service ne
    connaît pas le catalogue des matières, il vit dans auth_db)."""
    exclusions = {"non_clos": 0, "sans_snapshot": 0, "hors_snapshot": 0}
    cartes = []
    for examen_id, statut, nom, date_examen in db.examens_par_matiere(matiere_id):
        if statut not in db.STATUTS_CLOS:
            exclusions["non_clos"] += 1
            continue
        snap = db.resolve_matiere(examen_id)
        if snap is None:
            exclusions["sans_snapshot"] += 1
            continue
        if snap != matiere_id:
            exclusions["hors_snapshot"] += 1
            continue
        carte, _ = _carte(examen_id, nom, date_examen, statut)
        cartes.append(carte)
    lectures = []
    if not cartes:
        lectures.append({"code": AUCUN_EXAMEN_CLOS, "raison": RAISON_AUCUN_EXAMEN_CLOS})
    return {
        "matiere_id": matiere_id,
        "examens": _trier(cartes),
        "exclusions": exclusions,
        "lectures": lectures,
    }


# ── Synthèse de la faculté — agrégé d'abord ──────────────────────────────────

def _agregat(sur20: list[float]) -> dict:
    """Médiane /20 et taux de réussite POOLÉS (tous étudiants de toutes les
    sessions, chacun ramené /20 — réussite = ≥ 10/20, le même seuil que
    ``resume``). Sous ``SEUIL_N_SYNTHESE`` : refus nommé, aucun nombre nu."""
    n = len(sur20)
    if n < SEUIL_N_SYNTHESE:
        return {
            "n_etudiants": n, "mediane_sur_20": None, "taux_reussite": None,
            "statut": engine.NON_CONCLUANT,
            "raison": engine._refus_effectif(n, SEUIL_N_SYNTHESE),
        }
    return {
        "n_etudiants": n,
        "mediane_sur_20": float(statistics.median(sur20)),
        "taux_reussite": sum(1 for v in sur20 if v >= SUR_20 / 2) / n,
        "statut": engine.CONCLUANT,
        "raison": None,
    }


def _session_publique(carte: dict) -> dict:
    """La ligne de session que la synthèse expose : des agrégats seulement,
    sous la lecture EFFECTIVE (#401)."""
    o = carte["lecture"]
    return {
        "examen_id": carte["examen_id"],
        "nom": carte["nom"],
        "date_examen": carte["date_examen"],
        "n_etudiants": o["n_etudiants"],
        "taux_reussite": o["taux_reussite"],
        "mediane_sur_20": (
            o["mediane"] * SUR_20 / o["denominateur"]
            if o["mediane"] is not None and o["denominateur"] else None
        ),
        "bareme_version": carte["bareme_version"],
        "lecture_officielle": carte["lecture_officielle"],
        "lectures": carte["lectures"],
    }


def synthese_faculte() -> dict:
    """Agrégats inter-matières pour le SUPER_ADMIN — par matière (clé = le
    SNAPSHOT scoring), puis la faculté. Les totaux par étudiant sont consommés
    ici et jetés : la réponse ne porte aucun identifiant d'étudiant, de
    notation ni d'évaluateur (invariant verrouillé par test)."""
    par_matiere: dict[int, dict] = {}
    exclusions = {"sans_snapshot": 0}
    for examen_id, matiere_vivante, statut, nom, date_examen in db.examens_clos_toutes_matieres():
        if statut not in db.STATUTS_CLOS:
            continue  # la requête ne rend que des clos ; ceinture
        snap = db.resolve_matiere(examen_id)
        if snap is None:
            exclusions["sans_snapshot"] += 1
            continue
        carte, sur20 = _carte(examen_id, nom, date_examen, statut)
        m = par_matiere.setdefault(snap, {"sessions": [], "sur20": [], "hors_snapshot": 0})
        if matiere_vivante != snap:
            # dérive vivante ≠ snapshot — dite ; l'examen reste dans SA matière snapshot
            m["hors_snapshot"] += 1
        m["sessions"].append(_session_publique(carte))
        m["sur20"].extend(sur20)

    matieres = []
    tous_sur20: list[float] = []
    for matiere_id, m in sorted(par_matiere.items()):
        sessions = _trier(m["sessions"])
        matieres.append({
            "matiere_id": matiere_id,
            "nb_examens_clos": len(sessions),
            "nb_avec_bareme_delibere": sum(1 for s in sessions if s["bareme_version"] is not None),
            "dernier_examen": (
                {k: sessions[-1][k] for k in ("examen_id", "nom", "date_examen")}
                if sessions else None
            ),
            "hors_snapshot": m["hors_snapshot"],
            **_agregat(m["sur20"]),
            "sessions": sessions,
        })
        tous_sur20.extend(m["sur20"])

    return {
        "faculte": {
            "nb_matieres": len(matieres),
            "nb_examens_clos": sum(x["nb_examens_clos"] for x in matieres),
            **_agregat(tous_sur20),
        },
        "matieres": matieres,
        "exclusions": exclusions,
    }
