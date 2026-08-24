"""L'orchestration par examen (#357) — la forme de résultat que #359 mettra en
cache (clé ``(examen_id, hash_des_entrées)``, ADR-0029 D3) et servira sur
``/ai/examens/{id}/indices``.

Règle de matrice : pour α et la discrimination, une notation n'entre dans la
matrice de sa grille que si son détail couvre TOUTES les feuilles (valeur non
NULL partout). Les autres sont comptées ``detail_incomplet`` — dites, jamais
tues. La difficulté, elle, se calcule critère par critère sur toutes les
valeurs présentes. Le ``saisi_par`` NULL n'écarte RIEN ici (le jugement est
valide) : le compteur existe pour les analyses évaluateur de N6 (#269 : jamais
back-fillé).

CLI de vérification en direct : ``python -m app.stats.runner <examen_id>``.
"""

import json
import sys
from dataclasses import replace

from app.stats import engine
from app.stats.loader import DonneesExamen, charger_examen
from app.stats.types import CritereDef, NotationChargee


def _feuilles_par_grille(criteres: dict[int, CritereDef]) -> dict[int, list[CritereDef]]:
    grilles: dict[int, list[CritereDef]] = {}
    for c in criteres.values():
        grilles.setdefault(c.grille_id, []).append(c)
    return grilles


def _detail_complet(n: NotationChargee, feuilles: list[CritereDef]) -> bool:
    return all(n.valeurs.get(c.item_id) is not None for c in feuilles)


def calculer_indices(examen_id: int, donnees: DonneesExamen | None = None) -> dict:
    """Tous les indices d'un examen — chaque valeur avec son statut et son IC."""
    d = donnees if donnees is not None else charger_examen(examen_id)
    grilles = _feuilles_par_grille(d.criteres)

    # Matrices par grille : notations à détail COMPLET uniquement.
    completes_par_grille: dict[int, list[NotationChargee]] = {}
    detail_incomplet = 0
    for n in d.notations:
        feuilles = grilles.get(n.grille_id, [])
        if not feuilles:
            continue
        if _detail_complet(n, feuilles):
            completes_par_grille.setdefault(n.grille_id, []).append(n)
        else:
            detail_incomplet += 1

    par_critere = []
    for grille_id, feuilles in sorted(grilles.items()):
        completes = completes_par_grille.get(grille_id, [])
        # Totaux pondérés par notation (arithmétique de ExamItemSnapshot.weigh).
        totaux = [
            sum(
                engine.contribution(c.type, n.valeurs[c.item_id], c.ponderation)
                for c in feuilles
            )
            for n in completes
        ]
        for c in feuilles:
            # Difficulté : toutes les valeurs présentes pour CE critère,
            # détail partiel compris (le jugement item existe).
            valeurs_item = [
                n.valeurs[c.item_id]
                for n in d.notations
                if n.grille_id == grille_id and n.valeurs.get(c.item_id) is not None
            ]
            diff = engine.difficulte(
                type_item=c.type, valeur_max=c.valeur_max, valeurs=valeurs_item
            )
            contribs = [
                engine.contribution(c.type, n.valeurs[c.item_id], c.ponderation)
                for n in completes
            ]
            disc = engine.discrimination(
                contributions_item=contribs,
                totaux_sans_item=[t - x for t, x in zip(totaux, contribs)],
            )
            par_critere.append(
                {
                    "item_id": c.item_id,
                    "libelle": c.libelle,
                    "type": c.type,
                    "grille_id": grille_id,
                    "station_id": c.station_id,
                    "difficulte": diff.as_dict(),
                    "discrimination": disc.as_dict(),
                }
            )

    par_grille = []
    for grille_id, feuilles in sorted(grilles.items()):
        completes = completes_par_grille.get(grille_id, [])
        matrice = [
            [
                engine.contribution(c.type, n.valeurs[c.item_id], c.ponderation)
                for c in feuilles
            ]
            for n in completes
        ]
        alpha = engine.alpha_cronbach(matrice=matrice)
        par_grille.append(
            {
                "grille_id": grille_id,
                "station_id": feuilles[0].station_id,
                "alpha_cronbach": alpha.as_dict(),
            }
        )

    # Concentration d'échec : par station, sur score_final vs note_max/2.
    note_max_par_station = {c.station_id: c.note_max for c in d.criteres.values()}
    stations: dict[int, dict[str, int]] = {}
    for n in d.notations:
        note_max = note_max_par_station.get(n.station_id)
        if n.score_final is None or note_max is None:
            continue
        s = stations.setdefault(n.station_id, {"echecs": 0, "n": 0})
        s["n"] += 1
        if n.score_final < note_max / 2:
            s["echecs"] += 1
    par_station = []
    for station_id, s in sorted(stations.items()):
        autres = [v for sid, v in stations.items() if sid != station_id]
        conc = engine.concentration_echec(
            echecs_station=s["echecs"],
            n_station=s["n"],
            echecs_autres=sum(a["echecs"] for a in autres),
            n_autres=sum(a["n"] for a in autres),
        )
        par_station.append({"station_id": station_id, "concentration_echec": conc.as_dict()})

    exclusions = replace(d.exclusions, detail_incomplet=detail_incomplet)
    return {
        "examen_id": examen_id,
        "exclusions": {
            "saisi_par_null": exclusions.saisi_par_null,
            "detail_incomplet": exclusions.detail_incomplet,
            "notations_analysees": exclusions.notations_analysees,
        },
        "par_critere": par_critere,
        "par_grille": par_grille,
        "par_station": par_station,
    }


def calculer_evaluateurs(examen_id: int, donnees: DonneesExamen | None = None) -> dict:
    """Sévérité par évaluateur, INTRA-STATION uniquement (#359, ADR-0021 D1/D2).

    Population : notations verrouillées à détail COMPLET (les totaux partiels
    ne sont pas comparables) ET attribuées (`saisi_par` non NULL — les
    notations pré-V15 participent aux indices d'items mais JAMAIS aux analyses
    évaluateur, décision #269, comptées dans les exclusions). Le total est la
    somme des contributions pondérées — même arithmétique que la
    discrimination, vérifiable au tableur.

    Une station à évaluateur unique (le cas nominal #163 hors suppléance et
    hors cohorte F1) rend un refus NOMMÉ, jamais un rang : le signal n'existe
    que là où la comparaison est défendable. Le résultat rend des IDS
    (`saisi_par`) — les noms vivent dans auth_db, hors du périmètre ai_reader ;
    le client web les résout via /api/v1/users (#80).
    """
    d = donnees if donnees is not None else charger_examen(examen_id)
    grilles = _feuilles_par_grille(d.criteres)

    detail_incomplet = 0
    # station_id → saisi_par → [totaux pondérés]
    par_station_eval: dict[int, dict[int, list[float]]] = {}
    for n in d.notations:
        feuilles = grilles.get(n.grille_id, [])
        if not feuilles:
            continue
        if not _detail_complet(n, feuilles):
            detail_incomplet += 1
            continue
        if n.saisi_par is None:
            continue  # comptée dans exclusions.saisi_par_null (loader)
        total = sum(
            engine.contribution(c.type, n.valeurs[c.item_id], c.ponderation)
            for c in feuilles
        )
        par_station_eval.setdefault(n.station_id, {}).setdefault(n.saisi_par, []).append(total)

    par_station = []
    for station_id, par_eval in sorted(par_station_eval.items()):
        evaluateurs = []
        for evaluateur_id, totaux in sorted(par_eval.items()):
            if len(par_eval) < 2:
                indice = engine.Indice(
                    code="SEVERITE_EVALUATEUR",
                    statut=engine.NON_CONCLUANT,
                    n=len(totaux),
                    raison=engine.REFUS_SEUL_EVALUATEUR,
                )
            else:
                autres = [
                    t
                    for autre_id, ts in par_eval.items()
                    if autre_id != evaluateur_id
                    for t in ts
                ]
                indice = engine.severite_evaluateur(
                    totaux_evaluateur=totaux, totaux_autres=autres
                )
            evaluateurs.append(
                {
                    "evaluateur_id": evaluateur_id,
                    "n": len(totaux),
                    "severite": indice.as_dict(),
                }
            )
        par_station.append(
            {
                "station_id": station_id,
                "nb_evaluateurs": len(par_eval),
                "evaluateurs": evaluateurs,
            }
        )

    exclusions = replace(d.exclusions, detail_incomplet=detail_incomplet)
    return {
        "examen_id": examen_id,
        "exclusions": {
            "saisi_par_null": exclusions.saisi_par_null,
            "detail_incomplet": exclusions.detail_incomplet,
            "notations_analysees": exclusions.notations_analysees,
        },
        "par_station": par_station,
    }


if __name__ == "__main__":
    if len(sys.argv) not in (2, 3) or (len(sys.argv) == 3 and sys.argv[2] != "evaluateurs"):
        print("usage: python -m app.stats.runner <examen_id> [evaluateurs]", file=sys.stderr)
        raise SystemExit(2)
    calcul = calculer_evaluateurs if len(sys.argv) == 3 else calculer_indices
    print(json.dumps(calcul(int(sys.argv[1])), ensure_ascii=False, indent=2))
