"""Vues ``v_ai_*`` → entrées du moteur. Le croisement scoring×exam se fait ICI,
en Python — Postgres ne joint pas entre bases (docstring de ``app.db``).

Sources (rôle ``ai_reader``, SELECT-only, statement_timeout 5 s côté serveur) :
- scoring ``v_ai_notations_verrouillees`` : une ligne par critère noté d'une
  notation VERROUILLÉE — l'exclusion des notations non verrouillées et des
  notations sans détail est STRUCTURELLE (la vue même) ;
- exam ``v_ai_criteres`` : libellé, type, ponderation, ``valeur_max`` (NULL pour
  les BINAIRE), ``note_max`` de la grille — absents du snapshot scoring, c'est
  la raison d'être du double GRANT (ADR-0029 D2).

Limite ASSUMÉE (à lever en N6/#359 si besoin) : une notation verrouillée SANS
AUCUN item n'apparaît pas dans la vue — elle est invisible d'ici, donc pas
comptable. Le compteur ``detail_incomplet`` ne couvre que le cas partiel
(des items présents mais pas tous). En pratique la garde de complétude #331
rend le verrouillage impossible sans détail complet.

Échec bruyant : toute erreur DB remonte telle quelle (le 503 de l'appelant,
ADR-0029 D7) — aucun repli qui fabriquerait des données.
"""

from collections.abc import Callable, Sequence

import psycopg

from app import db
from app.stats.types import CritereDef, Exclusions, NotationChargee

_SQL_NOTATIONS = """
    SELECT notation_id, station_id, grille_id, etudiant_id, saisi_par,
           score_final, item_id, valeur
    FROM v_ai_notations_verrouillees
    WHERE examen_id = %s
    ORDER BY notation_id, item_id
"""

_SQL_CRITERES = """
    SELECT item_id, libelle, type, ponderation, valeur_max,
           grille_id, station_id, note_max, parent_id
    FROM v_ai_criteres
    WHERE examen_id = %s
    ORDER BY grille_id, ordre, item_id
"""


def _lire_notations(examen_id: int) -> list[tuple]:
    with psycopg.connect(db.SCORING_DSN, connect_timeout=db._CONNECT_TIMEOUT) as conn:
        return conn.execute(_SQL_NOTATIONS, (examen_id,)).fetchall()


def _lire_criteres(examen_id: int) -> list[tuple]:
    with psycopg.connect(db.EXAM_DSN, connect_timeout=db._CONNECT_TIMEOUT) as conn:
        return conn.execute(_SQL_CRITERES, (examen_id,)).fetchall()


class DonneesExamen:
    """Les entrées du moteur pour UN examen, plus les compteurs d'exclusion."""

    def __init__(
        self,
        criteres: dict[int, CritereDef],
        notations: list[NotationChargee],
        exclusions: Exclusions,
    ):
        self.criteres = criteres
        self.notations = notations
        self.exclusions = exclusions


def charger_examen(
    examen_id: int,
    *,
    lire_notations: Callable[[int], Sequence[tuple]] = _lire_notations,
    lire_criteres: Callable[[int], Sequence[tuple]] = _lire_criteres,
) -> DonneesExamen:
    """Charge et croise les deux vues. Les lectures sont injectables (tests)."""
    # ⚠️ v_ai_criteres rend TOUS les items, PARENTS hiérarchiques compris
    # (contrairement au snapshot scoring, qui ne fige que les feuilles).
    # Seules les FEUILLES sont notables (#160) : un parent n'a jamais de
    # valeur, il ferait croire à un détail incomplet et viderait les matrices
    # — bug trouvé au drill live sur l'examen 77. Feuille = item jamais
    # référencé comme parent_id par un autre item.
    lignes = list(lire_criteres(examen_id))
    ids_parents = {parent_id for *_, parent_id in lignes if parent_id is not None}
    criteres: dict[int, CritereDef] = {}
    for item_id, libelle, type_, ponderation, valeur_max, grille_id, station_id, note_max, _parent_id in lignes:
        if item_id in ids_parents:
            continue  # parent hiérarchique — non notable, hors moteur
        criteres[item_id] = CritereDef(
            item_id=item_id,
            libelle=libelle,
            type=type_,
            ponderation=float(ponderation) if ponderation is not None else 0.0,
            valeur_max=float(valeur_max) if valeur_max is not None else None,
            grille_id=grille_id,
            station_id=station_id,
            note_max=float(note_max),
        )

    # Regroupe les lignes (notation, item) par notation.
    par_notation: dict[int, dict] = {}
    for notation_id, station_id, grille_id, etudiant_id, saisi_par, score_final, item_id, valeur in lire_notations(examen_id):
        n = par_notation.setdefault(
            notation_id,
            {
                "station_id": station_id,
                "grille_id": grille_id,
                "etudiant_id": etudiant_id,
                "saisi_par": saisi_par,
                "score_final": float(score_final) if score_final is not None else None,
                "valeurs": {},
            },
        )
        n["valeurs"][item_id] = float(valeur) if valeur is not None else None

    saisi_par_null = sum(1 for n in par_notation.values() if n["saisi_par"] is None)
    notations = [
        NotationChargee(notation_id=nid, **champs) for nid, champs in par_notation.items()
    ]
    exclusions = Exclusions(
        saisi_par_null=saisi_par_null,
        detail_incomplet=0,  # posé par le runner, qui connaît les feuilles par grille
        notations_analysees=len(notations),
    )
    return DonneesExamen(criteres=criteres, notations=notations, exclusions=exclusions)
