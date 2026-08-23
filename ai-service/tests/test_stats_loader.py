"""Le loader — croisement des deux vues en Python, lectures injectées (pas de DB)."""

from app.stats.loader import charger_examen

# Colonnes de v_ai_criteres (ordre du SELECT du loader) :
# item_id, libelle, type, ponderation, valeur_max, grille_id, station_id, note_max, parent_id
_CRITERES = [
    (1, "Hygiène des mains", "BINAIRE", 5.0, None, 10, 101, 20.0, None),
    (2, "Calcul de dose", "NUMERIQUE", 10.0, 10.0, 10, 101, 20.0, None),
]

# Hiérarchie réelle (examen 77) : un PARENT décomposé + ses deux feuilles.
_CRITERES_HIERARCHIQUES = [
    (187, "Préparation du dosage", "BINAIRE", 3.0, None, 10, 101, 20.0, None),   # parent
    (194, "Choix de l'indicateur", "BINAIRE", 2.0, None, 10, 101, 20.0, 187),    # feuille
    (195, "Vérification du titre", "BINAIRE", 1.0, None, 10, 101, 20.0, 187),    # feuille
    (192, "Calcul de la masse", "NUMERIQUE", 6.0, 6.0, 10, 101, 20.0, None),     # feuille simple
]

# Colonnes de v_ai_notations_verrouillees (ordre du SELECT du loader) :
# notation_id, station_id, grille_id, etudiant_id, saisi_par, score_final, item_id, valeur
_NOTATIONS = [
    (50, 101, 10, 7, 67, 13.0, 1, 1.0),
    (50, 101, 10, 7, 67, 13.0, 2, 8.0),
    (51, 101, 10, 8, None, 6.0, 1, 0.0),  # saisi_par NULL (pré-V15)
    (51, 101, 10, 8, None, 6.0, 2, 6.0),
]


def test_croisement_et_regroupement_par_notation():
    d = charger_examen(
        77,
        lire_notations=lambda _: _NOTATIONS,
        lire_criteres=lambda _: _CRITERES,
    )
    assert set(d.criteres) == {1, 2}
    assert d.criteres[1].valeur_max is None  # BINAIRE — NULL par construction
    assert d.criteres[2].note_max == 20.0
    assert len(d.notations) == 2
    n50 = next(n for n in d.notations if n.notation_id == 50)
    assert n50.valeurs == {1: 1.0, 2: 8.0}
    assert n50.score_final == 13.0


def test_compteur_saisi_par_null():
    """Décision #269 : jamais back-fillé — exclu des analyses évaluateur (N6)
    et COMPTÉ comme tel dès le chargement."""
    d = charger_examen(
        77,
        lire_notations=lambda _: _NOTATIONS,
        lire_criteres=lambda _: _CRITERES,
    )
    assert d.exclusions.saisi_par_null == 1
    assert d.exclusions.notations_analysees == 2


def test_parents_hierarchiques_filtres_seules_les_feuilles_restent():
    """v_ai_criteres rend TOUS les items, parents compris — mais seules les
    FEUILLES sont notables (#160). Un parent gardé viderait les matrices
    (« détail incomplet » perpétuel) — bug trouvé au drill live sur l'examen 77."""
    d = charger_examen(
        77,
        lire_notations=lambda _: [],
        lire_criteres=lambda _: _CRITERES_HIERARCHIQUES,
    )
    assert set(d.criteres) == {194, 195, 192}  # 187 (parent) écarté
    assert 187 not in d.criteres


def test_examen_vide():
    d = charger_examen(999, lire_notations=lambda _: [], lire_criteres=lambda _: [])
    assert d.criteres == {}
    assert d.notations == []
    assert d.exclusions.notations_analysees == 0
