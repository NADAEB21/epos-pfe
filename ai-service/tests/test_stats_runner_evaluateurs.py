"""``calculer_evaluateurs`` (#359) — le regroupement intra-station du runner.

Vérifie ce que l'engine seul ne voit pas : le découpage par (station,
saisi_par), le pool « les autres de la MÊME station », l'écartement des
détails incomplets et des ``saisi_par`` NULL, et le refus nommé de la station
à évaluateur unique.
"""

from app.stats.loader import DonneesExamen
from app.stats.runner import calculer_evaluateurs
from app.stats.types import CritereDef, Exclusions, NotationChargee


def _critere(item_id: int, station_id: int, grille_id: int) -> CritereDef:
    return CritereDef(item_id=item_id, libelle=f"C{item_id}", type="NUMERIQUE",
                      ponderation=10.0, valeur_max=10.0, grille_id=grille_id,
                      station_id=station_id, note_max=10.0)


def _notation(nid: int, station: int, grille: int, saisi_par: int | None,
              valeur: float | None) -> NotationChargee:
    item = 1 if station == 9 else 2
    return NotationChargee(notation_id=nid, station_id=station, grille_id=grille,
                           etudiant_id=200 + nid, saisi_par=saisi_par,
                           score_final=valeur, valeurs={item: valeur})


def _donnees_deux_evaluateurs() -> DonneesExamen:
    """Station 9 (grille 5) : Leila (67) décalée de +2 sur Karim (3) — le défaut
    planté F1, 10 copies chacun. Station 12 (grille 6) : un seul évaluateur.
    Une notation NULL et une à détail incomplet, écartées et comptées."""
    notations = (
        [_notation(i, 9, 5, 67, 8.0) for i in range(1, 11)]
        + [_notation(i, 9, 5, 3, 6.0) for i in range(11, 21)]
        + [_notation(30, 12, 6, 44, 5.0)]
        + [_notation(31, 9, 5, None, 7.0)]   # pré-V15 → hors population, comptée
        + [_notation(32, 9, 5, 67, None)]    # détail incomplet → écartée, comptée
    )
    return DonneesExamen(
        criteres={1: _critere(1, 9, 5), 2: _critere(2, 12, 6)},
        notations=notations,
        exclusions=Exclusions(saisi_par_null=1, detail_incomplet=0,
                              notations_analysees=len(notations)),
    )


def test_le_defaut_plante_f1_est_retrouve_a_la_valeur_exacte():
    resultat = calculer_evaluateurs(77, _donnees_deux_evaluateurs())

    station9 = next(s for s in resultat["par_station"] if s["station_id"] == 9)
    assert station9["nb_evaluateurs"] == 2

    leila = next(e for e in station9["evaluateurs"] if e["evaluateur_id"] == 67)
    karim = next(e for e in station9["evaluateurs"] if e["evaluateur_id"] == 3)
    assert leila["n"] == 10 and karim["n"] == 10
    assert leila["severite"]["statut"] == "CONCLUANT"
    assert leila["severite"]["valeur"] == 2.0    # 8,0 − 6,0 : calculable de tête
    assert karim["severite"]["valeur"] == -2.0   # la symétrie exacte du même écart


def test_station_a_evaluateur_unique_refus_nomme():
    resultat = calculer_evaluateurs(77, _donnees_deux_evaluateurs())
    station12 = next(s for s in resultat["par_station"] if s["station_id"] == 12)
    assert station12["nb_evaluateurs"] == 1
    severite = station12["evaluateurs"][0]["severite"]
    assert severite["statut"] == "NON_CONCLUANT"
    assert "un seul évaluateur" in severite["raison"]


def test_exclusions_dites_null_et_detail_incomplet():
    resultat = calculer_evaluateurs(77, _donnees_deux_evaluateurs())
    assert resultat["exclusions"]["saisi_par_null"] == 1
    assert resultat["exclusions"]["detail_incomplet"] == 1
    # Et la notation NULL n'a créé AUCUNE entrée évaluateur.
    ids = [e["evaluateur_id"]
           for s in resultat["par_station"] for e in s["evaluateurs"]]
    assert None not in ids
