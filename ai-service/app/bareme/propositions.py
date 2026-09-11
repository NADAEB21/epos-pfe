"""Le moteur de proposition (#362 / N8) — des indices N5 aux propositions D8.

Trois opérations, dans l'ordre de défendabilité d'ADR-0021 D8, et seulement
elles (l'énumération fermée de scoring) :
1. ``EXCLURE_CRITERE`` — « personne n'a pu marquer » / « n'a séparé personne »
   sont des OBSERVATIONS (rang 1) ;
2. ``EXCLURE_STATION`` — la station en échec, le cas de l'encadrant (rang 2) ;
3. ``REPONDERER`` — permis, le plus faible : « le critère 1 aurait dû compter
   plus » est un JUGEMENT, pas une observation. **Jamais proposé
   automatiquement** — le module le dit ; l'arithmétique est disponible via
   ``/projection`` pour qu'un responsable prévisualise SA repondération avant
   de l'écrire (D10).

SEUILS — NOTRE choix, comme ``SEUIL_N_*`` de ``engine`` : aucun ADR ne fixe
un déclencheur chiffré (ADR-0029 D6 exige seulement des « gabarits pilotés
par seuils »). Une proposition ne part JAMAIS d'un indice ``NON_CONCLUANT`` :
le contrat de refus du moteur s'applique aux propositions comme aux nombres.

Pourquoi la discrimination est GARDÉE par l'α de la grille : r est la
corrélation de l'item au RESTE de sa grille — quand ce reste est incohérent
(α faible), r ne dit rien de l'item. Sur la cohorte F1 (examen 80), le
critère planté « sans lien » (r=−0,08) et le critère SAIN « Geste conforme »
(r=−0,03) sont statistiquement indistinguables dans la Station Défauts
(α=0,06 ; retirer l'un OU l'autre relève α). Un module honnête ne choisit pas
entre les deux : il dit la station incohérente (``GRILLE_INCOHERENTE``) et ne
propose aucun retrait d'item là — décision Nada, S50 (2026-09-01).

Le module n'écrit JAMAIS vers scoring : il rend l'opération sous la forme
EXACTE du fil (``operations_a_soumettre`` = version courante + opération) et
le client web la POSTe par la porte N7, avec le motif du responsable (D1).
"""

from __future__ import annotations

import hashlib
import json
import math
from collections.abc import Mapping

from app.bareme import projection as pj
from app.stats.loader import DonneesExamen
from app.stats.types import CONCLUANT

# ── Seuils de déclenchement (NOS choix — documentés ici, versionnés par MOTEUR_VERSION) ──
SEUIL_P_IMPOSSIBLE = 0.10           # D8 op.1 — difficulté p ≤ 0,10 : « personne n'a pu marquer »
SEUIL_R_NUL = 0.10                  # D8 op.1 — |r| ≤ 0,10 : « n'a séparé personne »
SEUIL_ALPHA_REFERENCE = 0.50        # r n'est lisible que contre un total cohérent (α ≥ 0,50)
SEUIL_TAUX_ECHEC_STATION = 0.50     # D8 op.2 — la majorité de la station échoue…
SEUIL_P_VALUE_CONCENTRATION = 0.05  # …et significativement plus que les autres stations

# Rang de défendabilité (ADR-0021 D8).
RANG_EXCLURE_CRITERE = 1
RANG_EXCLURE_STATION = 2
RANG_REPONDERER = 3

# Codes de lecture — l'union FERMÉE que le client web (gabarits F4) traduit.
CRITERE_IMPOSSIBLE = "CRITERE_IMPOSSIBLE"
CRITERE_SANS_LIEN = "CRITERE_SANS_LIEN"
STATION_EN_ECHEC = "STATION_EN_ECHEC"
# Lectures SANS proposition — le silence est dit, jamais muet.
GRILLE_INCOHERENTE = "GRILLE_INCOHERENTE"
STATION_NON_SNAPSHOTEE = "STATION_NON_SNAPSHOTEE"
COUVERTURE_INCOMPLETE = "COUVERTURE_INCOMPLETE"
CIBLE_NON_DELIBERABLE = "CIBLE_NON_DELIBERABLE"
REPONDERATION_JAMAIS_AUTOMATIQUE = "REPONDERATION_JAMAIS_AUTOMATIQUE"


# ── Identité déterministe d'une proposition ──────────────────────────────────

def proposition_id(
    examen_id: int, entrees_hash: str, moteur_version: str,
    version_base: int | None, operation: pj.Operation,
) -> str:
    """sha256 tronqué (32 hex) de tout ce qui définit la proposition : même
    examen, mêmes entrées, même moteur, même barème de base, même opération ⇒
    même id. Un re-GET ne duplique pas ; une donnée qui bouge crée un id neuf."""
    op = json.dumps(operation.as_wire(), sort_keys=True, separators=(",", ":"))
    brut = f"{examen_id}|{entrees_hash}|{moteur_version}|{version_base}|{op}"
    return hashlib.sha256(brut.encode("utf-8")).hexdigest()[:32]


# ── Conversion des lignes de vues en formes ──────────────────────────────────

def bareme_depuis_lignes(lignes: list[tuple]) -> pj.BaremeCourant | None:
    """``db.bareme_courant`` → ``BaremeCourant`` (None = aucun barème)."""
    if not lignes:
        return None
    version = int(lignes[0][0])
    ops = []
    for _version, op_type, item_id, station_id, echelle in lignes:
        if op_type is None:
            continue  # version vide (LEFT JOIN) — aucune opération
        ops.append(pj.Operation(
            type=str(op_type),
            cible_item_id=int(item_id) if item_id is not None else None,
            cible_station_id=int(station_id) if station_id is not None else None,
            nouvelle_echelle=float(echelle) if echelle is not None else None,
        ))
    return pj.BaremeCourant(version=version, operations=tuple(ops))


def grilles_depuis_lignes(lignes: list[tuple]) -> dict[int, pj.GrilleSnap]:
    """``db.grilles_snapshot`` → station_id → GrilleSnap."""
    return {
        int(station_id): pj.GrilleSnap(
            station_id=int(station_id), grille_id=int(grille_id),
            note_max=float(note_max), items_json=items_json,
        )
        for station_id, grille_id, note_max, items_json in lignes
    }


def items_snapshotes(donnees: DonneesExamen) -> frozenset[int]:
    """Les critères présents dans ``exam_item_snapshot`` : ceux vus dans au
    moins une notation de la vue V20 (jointure INTERNE sur le snapshot)."""
    ids: set[int] = set()
    for n in donnees.notations:
        ids.update(n.valeurs.keys())
    return frozenset(ids)


# ── Déclencheurs ─────────────────────────────────────────────────────────────

def _concluant(indice: Mapping | None) -> bool:
    return bool(indice) and indice.get("statut") == CONCLUANT and indice.get("valeur") is not None


def _declencheur(indice: Mapping, seuil: float, regle: str) -> dict:
    return {
        "code": indice["code"], "valeur": indice["valeur"], "ic": indice.get("ic"),
        "n": indice.get("n"), "seuil": seuil, "regle": regle,
    }


def candidats(indices: Mapping) -> tuple[list[dict], list[dict]]:
    """Depuis le payload ``indices`` (runner.calculer_indices) : les candidats
    (opération + rang + lecture + déclencheurs) et les lectures sans
    proposition d'origine statistique (``GRILLE_INCOHERENTE``).
    """
    alpha_par_grille = {
        g["grille_id"]: g["alpha_cronbach"] for g in indices.get("par_grille", [])
    }
    liste: list[dict] = []
    lectures: list[dict] = []
    r_nuls_par_grille: dict[int, list[int]] = {}
    k_par_grille: dict[int, int] = {}

    for c in indices.get("par_critere", []):
        grille_id = c["grille_id"]
        k_par_grille[grille_id] = k_par_grille.get(grille_id, 0) + 1
        diff = c.get("difficulte")
        disc = c.get("discrimination")
        alpha = alpha_par_grille.get(grille_id)

        if _concluant(diff) and diff["valeur"] <= SEUIL_P_IMPOSSIBLE:
            liste.append({
                "operation": pj.Operation(pj.EXCLURE_CRITERE, cible_item_id=c["item_id"]),
                "rang_defendabilite": RANG_EXCLURE_CRITERE,
                "lecture_code": CRITERE_IMPOSSIBLE,
                "cible": _cible_critere(c),
                "declencheur": [_declencheur(diff, SEUIL_P_IMPOSSIBLE, "p <= seuil")],
            })
            continue

        if _concluant(disc) and abs(disc["valeur"]) <= SEUIL_R_NUL:
            r_nuls_par_grille.setdefault(grille_id, []).append(c["item_id"])
            if _concluant(alpha) and alpha["valeur"] >= SEUIL_ALPHA_REFERENCE:
                liste.append({
                    "operation": pj.Operation(pj.EXCLURE_CRITERE, cible_item_id=c["item_id"]),
                    "rang_defendabilite": RANG_EXCLURE_CRITERE,
                    "lecture_code": CRITERE_SANS_LIEN,
                    "cible": _cible_critere(c),
                    "declencheur": [
                        _declencheur(disc, SEUIL_R_NUL, "|r| <= seuil"),
                        _declencheur(alpha, SEUIL_ALPHA_REFERENCE, "alpha >= seuil (total de référence cohérent)"),
                    ],
                })

    # Grille incohérente : α CONCLUANT sous la référence ET au moins la moitié
    # des critères à r≈0 — la lecture est celle de la GRILLE, pas d'un item.
    for grille_id, items in sorted(r_nuls_par_grille.items()):
        alpha = alpha_par_grille.get(grille_id)
        k = k_par_grille.get(grille_id, 0)
        if _concluant(alpha) and alpha["valeur"] < SEUIL_ALPHA_REFERENCE \
                and k > 0 and len(items) >= math.ceil(k / 2):
            station_id = next(
                (g["station_id"] for g in indices.get("par_grille", []) if g["grille_id"] == grille_id),
                None,
            )
            lectures.append({
                "code": GRILLE_INCOHERENTE,
                "grille_id": grille_id,
                "station_id": station_id,
                "details": {
                    "alpha": alpha["valeur"], "ic": alpha.get("ic"), "k": k,
                    "items_r_nul": sorted(items),
                    "seuil_alpha": SEUIL_ALPHA_REFERENCE, "seuil_r": SEUIL_R_NUL,
                },
                "raison": (
                    "le total de référence de cette grille est lui-même incohérent — "
                    "la discrimination de ses critères n'est pas interprétable, "
                    "aucun retrait de critère n'est proposé"
                ),
            })

    for s in indices.get("par_station", []):
        conc = s.get("concentration_echec")
        if not _concluant(conc):
            continue
        details = conc.get("details") or {}
        p_value = details.get("p_value")
        taux_autres = details.get("taux_autres")
        if p_value is None or taux_autres is None:
            continue
        if conc["valeur"] >= SEUIL_TAUX_ECHEC_STATION and conc["valeur"] > taux_autres \
                and p_value <= SEUIL_P_VALUE_CONCENTRATION:
            liste.append({
                "operation": pj.Operation(pj.EXCLURE_STATION, cible_station_id=s["station_id"]),
                "rang_defendabilite": RANG_EXCLURE_STATION,
                "lecture_code": STATION_EN_ECHEC,
                "cible": {"station_id": s["station_id"]},
                "declencheur": [{
                    **_declencheur(conc, SEUIL_TAUX_ECHEC_STATION,
                                   "taux_echec >= seuil ET p_value <= seuil_p"),
                    "p_value": p_value, "taux_autres": taux_autres,
                    "seuil_p": SEUIL_P_VALUE_CONCENTRATION,
                    "echecs_station": details.get("echecs_station"),
                    "n_autres": details.get("n_autres"),
                }],
            })

    liste.sort(key=lambda p: (p["rang_defendabilite"],
                              p["operation"].cible_station_id or 0,
                              p["operation"].cible_item_id or 0))
    return liste, lectures


def _cible_critere(c: Mapping) -> dict:
    return {
        "item_id": c["item_id"], "libelle": c.get("libelle"), "type": c.get("type"),
        "grille_id": c["grille_id"], "station_id": c.get("station_id"),
    }


# ── Assemblage du payload /propositions ──────────────────────────────────────

def construire(
    *,
    examen_id: int,
    entrees_hash: str,
    moteur_version: str,
    donnees: DonneesExamen,
    indices: Mapping,
    courant: pj.BaremeCourant | None,
    grilles: Mapping[int, pj.GrilleSnap],
    decisions: Mapping[str, Mapping],
) -> dict:
    """Le payload complet de ``GET /ai/examens/{id}/propositions``.

    Chaque candidat est validé comme scoring le validerait (``pj.valider``) :
    ce que scoring refuserait n'est pas proposé, il est DIT
    (``CIBLE_NON_DELIBERABLE`` / ``STATION_NON_SNAPSHOTEE``). L'effet est
    projeté contre le barème COURANT (avant) et le barème proposé (après) ;
    ``deja_appliquee`` vient de la DONNÉE (l'opération figure dans la version
    courante), ``decision`` du JOURNAL — jamais l'un déduit de l'autre.
    """
    ops_courantes = list(courant.operations) if courant else []
    version_base = courant.version if courant else None
    snapshotes = items_snapshotes(donnees)
    applique_avant = pj.appliquer(ops_courantes, donnees.criteres, grilles) if courant else None
    t_ref = pj.totaux(applique_avant, donnees, grilles)
    couverture = t_ref.couverture_complete

    liste, lectures = candidats(indices)
    if not couverture:
        lectures.append({
            "code": COUVERTURE_INCOMPLETE,
            "details": {
                "stations_snapshotees": sorted(grilles.keys()),
                "stations_notees": sorted({n.station_id for n in donnees.notations
                                           if n.station_id is not None}),
            },
            "raison": (
                "une station notée n'a pas de barème enregistré au moment de l'épreuve — "
                "aucun total délibéré ne peut être calculé, aucun effet ne peut être montré"
            ),
        })

    # Les décisions du journal, indexées par proposition_id ET par opération :
    # une opération déjà appliquée porte un id NEUF (la version de base a
    # changé) — sa décision historique doit rester visible. Seules les lignes
    # DÉCIDÉES comptent ; une ligne encore ouverte n'est pas une décision.
    decidees = {pid: l for pid, l in decisions.items() if l.get("decision")}
    par_operation: dict[str, Mapping] = {}
    for l in sorted(decidees.values(), key=lambda l: str(l.get("decide_a") or "")):
        par_operation[_cle_operation(l.get("operation") or {})] = l

    propositions = []
    for cand in liste:
        op: pj.Operation = cand["operation"]
        deja = any(pj.memes_operations([op], [o]) for o in ops_courantes)
        if deja:
            a_soumettre = ops_courantes
            refus = None
        else:
            a_soumettre = _composer(ops_courantes, op, donnees.criteres)
            refus = pj.valider(a_soumettre, donnees.criteres, snapshotes, grilles, courant)
        if refus is not None:
            code = STATION_NON_SNAPSHOTEE if refus.code == "GRILLE_NON_SNAPSHOTEE" else CIBLE_NON_DELIBERABLE
            lectures.append({
                "code": code,
                "lecture_code": cand["lecture_code"],
                "operation": op.as_wire(),
                "cible": cand["cible"],
                "declencheur": cand["declencheur"],
                "details": {"refus_scoring": refus.code},
                "raison": refus.detail,
            })
            continue

        applique_apres = pj.appliquer(a_soumettre, donnees.criteres, grilles)
        eff = pj.effet(applique_avant, applique_apres, donnees, grilles) if couverture else None
        pid = proposition_id(examen_id, entrees_hash, moteur_version, version_base, op)
        cible = dict(cand["cible"])
        if op.cible_item_id is not None:
            oi = applique_apres.operations_par_item.get(op.cible_item_id)
            cible["max"] = oi.max if oi else None
        else:
            g = grilles.get(op.cible_station_id)
            cible["max"] = g.note_max if g else None
        decision = decidees.get(pid) or par_operation.get(_cle_operation(op.as_wire()))
        propositions.append({
            "proposition_id": pid,
            "rang_defendabilite": cand["rang_defendabilite"],
            "lecture_code": cand["lecture_code"],
            "operation": op.as_wire(),
            "operations_a_soumettre": [o.as_wire() for o in a_soumettre],
            "cible": cible,
            "declencheur": cand["declencheur"],
            "effet_projete": eff,
            "deja_appliquee": deja,
            "decision": _decision_publique(decision) if decision else None,
        })

    lectures.append({
        "code": REPONDERATION_JAMAIS_AUTOMATIQUE,
        "details": {"rang_defendabilite": RANG_REPONDERER},
        "raison": (
            "repondérer un critère ou une station est un choix de jury, pas un constat — "
            "l'analyse ne le propose jamais d'elle-même ; vous pouvez le composer vous-même "
            "et en voir l'effet avant de décider"
        ),
    })

    return {
        "examen_id": examen_id,
        "entrees_hash": entrees_hash,
        "moteur_version": moteur_version,
        "bareme_courant": (
            {"version": courant.version, "operations": [o.as_wire() for o in courant.operations]}
            if courant else None
        ),
        "couverture_snapshot_complete": couverture,
        "seuils": {
            "p_impossible": SEUIL_P_IMPOSSIBLE, "r_nul": SEUIL_R_NUL,
            "alpha_reference": SEUIL_ALPHA_REFERENCE,
            "taux_echec_station": SEUIL_TAUX_ECHEC_STATION,
            "p_value_concentration": SEUIL_P_VALUE_CONCENTRATION,
        },
        "propositions": propositions,
        "lectures_sans_proposition": lectures,
    }


def _cle_operation(wire: Mapping) -> str:
    """Clé canonique d'une opération (forme du fil) — pour retrouver une
    décision par opération quand l'id de proposition a changé."""
    return json.dumps(pj.Operation.from_wire(wire).as_wire(), sort_keys=True, separators=(",", ":"))


def _composer(ops_courantes: list[pj.Operation], op: pj.Operation,
              criteres: Mapping) -> list[pj.Operation]:
    """La version COMPLÈTE à soumettre = version courante + l'opération
    proposée. Exclure une STATION rend caduques les opérations critère de
    cette station (et une repondération de la même station) : scoring refuse
    de les combiner (« un seul niveau à la fois ») et une station exclue sort
    entièrement du calcul — on les retire donc de la composition, au lieu de
    proposer une version que scoring rejetterait."""
    if op.type != pj.EXCLURE_STATION:
        return ops_courantes + [op]
    s = op.cible_station_id
    conservees = []
    for o in ops_courantes:
        if o.cible_station_id == s:
            continue
        if o.cible_item_id is not None:
            crit = criteres.get(o.cible_item_id)
            if crit is not None and crit.station_id == s:
                continue
        conservees.append(o)
    return conservees + [op]


def _decision_publique(ligne: Mapping) -> dict:
    return {
        "decision": ligne.get("decision"),
        "motif": ligne.get("motif"),
        "decide_par": ligne.get("decide_par"),
        "decide_a": ligne.get("decide_a"),
        "bareme_version_resultat": ligne.get("bareme_version_resultat"),
        # l'id de la ligne qui porte l'acte (≠ de l'id courant si la version
        # de base a changé depuis — une opération déjà appliquée, typiquement)
        "proposition_id": ligne.get("proposition_id"),
    }


def lignes_journal(payload: Mapping) -> list[dict]:
    """Les lignes à insérer dans le journal pour un payload construit — une
    opération DÉJÀ appliquée n'est pas une proposition (rien à décider) : pas
    de ligne pour elle, sa décision historique vit déjà au journal."""
    return [
        {
            "proposition_id": p["proposition_id"],
            "examen_id": payload["examen_id"],
            "entrees_hash": payload["entrees_hash"],
            "moteur_version": payload["moteur_version"],
            "bareme_version_base": payload["bareme_courant"]["version"] if payload["bareme_courant"] else None,
            "operation": p["operation"],
            "declencheur": p["declencheur"],
            "effet_projete": p["effet_projete"],
        }
        for p in payload["propositions"]
        if not p["deja_appliquee"]
    ]
