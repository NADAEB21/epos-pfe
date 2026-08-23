"""Les formes de données du moteur (#357) — dataclasses gelées, convention du socle.

Le résultat de CHAQUE indice porte le contrat de refus (ADR-0021 D2, ADR-0029 D6) :
sous les effectifs minimaux — ou sur une donnée incalculable — l'indice ne rend
JAMAIS un nombre nu : il rend un statut ``NON_CONCLUANT`` avec sa ``raison`` en
français, gabarit déterministe. Un indice qui refuse de répondre est l'indice
crédible.
"""

from dataclasses import asdict, dataclass, field


@dataclass(frozen=True)
class CritereDef:
    """Un critère (FEUILLE) tel que le barème l'a défini — vue exam ``v_ai_criteres``.

    ``valeur_max`` est NULL par construction pour un BINAIRE (et pour les parents
    hiérarchiques, qui n'apparaissent jamais ici : seules les feuilles sont
    notables/snapshotées). ``note_max`` est celui de la grille — le dénominateur
    du seuil d'échec de l'écran A (< 50 %).
    """

    item_id: int
    libelle: str
    type: str  # 'BINAIRE' | 'NUMERIQUE'
    ponderation: float
    valeur_max: float | None
    grille_id: int
    station_id: int
    note_max: float


@dataclass(frozen=True)
class NotationChargee:
    """Une notation VERROUILLÉE avec son détail par critère (vue scoring).

    ``valeurs`` : item_id → valeur saisie. Une notation sans AUCUN détail par
    critère n'apparaît pas dans la vue (jointure interne sur notation_items) —
    l'exclusion est structurelle, le compteur vit dans ``Exclusions``.
    """

    notation_id: int
    station_id: int
    grille_id: int
    etudiant_id: int | None
    saisi_par: int | None
    score_final: float | None
    valeurs: dict[int, float | None]


@dataclass(frozen=True)
class Exclusions:
    """Ce que le moteur a écarté — compté et DIT, jamais silencieux (#269).

    - ``saisi_par_null`` : notations verrouillées sans auteur (pré-V15) — elles
      participent aux indices d'items (le jugement est valide) mais seront
      exclues des analyses évaluateur (N6) ; on les compte dès maintenant.
    - ``detail_incomplet`` : notations dont le détail ne couvre pas toutes les
      feuilles de leur grille — écartées de la matrice (α, discrimination),
      gardées pour la difficulté critère par critère.
    """

    saisi_par_null: int = 0
    detail_incomplet: int = 0
    notations_analysees: int = 0


# Statuts du contrat de refus.
CONCLUANT = "CONCLUANT"
NON_CONCLUANT = "NON_CONCLUANT"


@dataclass(frozen=True)
class Indice:
    """Le résultat d'UN indice, incertitude comprise.

    ``valeur`` et ``ic`` ne sont renseignés que si ``statut == CONCLUANT`` ;
    ``raison`` n'est renseignée que sur un refus. ``details`` porte les
    grandeurs annexes propres à l'indice (taux, p-value, k…) — toujours des
    types JSON-sérialisables.
    """

    code: str  # 'DIFFICULTE' | 'DISCRIMINATION' | 'ALPHA_CRONBACH' | 'CONCENTRATION_ECHEC'
    statut: str
    n: int
    valeur: float | None = None
    ic: tuple[float, float] | None = None
    raison: str | None = None
    details: dict = field(default_factory=dict)

    def as_dict(self) -> dict:
        d = asdict(self)
        if self.ic is not None:
            d["ic"] = [self.ic[0], self.ic[1]]
        return d
