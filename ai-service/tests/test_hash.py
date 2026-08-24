"""L'empreinte des entrées (#359, ADR-0029 D3) — stable, sensible, versionnée.

Trois propriétés, une par test : mêmes entrées ⇒ même hash (reproductibilité
jury) ; une valeur qui bouge (réajustement) ⇒ hash différent (recalcul auto) ;
MOTEUR_VERSION dans le sel ⇒ une évolution du moteur invalide le cache sans
que la donnée bouge.
"""

from dataclasses import replace

from app.stats import hash as stats_hash
from app.stats.loader import DonneesExamen
from app.stats.types import CritereDef, Exclusions, NotationChargee


def _donnees(valeur_item_1: float = 6.0) -> DonneesExamen:
    criteres = {
        1: CritereDef(item_id=1, libelle="Pesée", type="NUMERIQUE", ponderation=10.0,
                      valeur_max=10.0, grille_id=5, station_id=9, note_max=20.0),
    }
    notations = [
        NotationChargee(notation_id=1, station_id=9, grille_id=5, etudiant_id=101,
                        saisi_par=67, score_final=valeur_item_1,
                        valeurs={1: valeur_item_1}),
    ]
    return DonneesExamen(criteres=criteres, notations=notations,
                         exclusions=Exclusions(notations_analysees=1))


def test_memes_entrees_meme_empreinte():
    assert stats_hash.empreinte(_donnees(), 1) == stats_hash.empreinte(_donnees(), 1)
    assert len(stats_hash.empreinte(_donnees(), 1)) == 64


def test_une_valeur_bouge_reajustement_empreinte_differente():
    """Le déclencheur du recalcul automatique (ADR-0013 → D3) : la donnée, pas l'horloge."""
    assert stats_hash.empreinte(_donnees(6.0), 1) != stats_hash.empreinte(_donnees(6.5), 1)


def test_total_verrouillees_fait_partie_des_entrees():
    """Une notation verrouillée SANS item est invisible des données chargées —
    seul le total V23 la voit. Sans lui dans le sel, son apparition ne
    changerait pas l'empreinte et « sans_aucun_item » resterait figé."""
    assert stats_hash.empreinte(_donnees(), 1) != stats_hash.empreinte(_donnees(), 2)


def test_version_moteur_fait_partie_du_sel(monkeypatch):
    avant = stats_hash.empreinte(_donnees(), 1)
    monkeypatch.setattr(stats_hash, "MOTEUR_VERSION", "n5-test-suivante")
    assert stats_hash.empreinte(_donnees(), 1) != avant


def test_ordre_d_arrivee_indifferent():
    """La sérialisation est canonique : l'ordre des lignes ne change pas l'empreinte."""
    d = _donnees()
    n2 = NotationChargee(notation_id=2, station_id=9, grille_id=5, etudiant_id=102,
                        saisi_par=67, score_final=4.0, valeurs={1: 4.0})
    ordre_a = DonneesExamen(criteres=d.criteres, notations=[d.notations[0], n2],
                            exclusions=d.exclusions)
    ordre_b = DonneesExamen(criteres=d.criteres, notations=[n2, d.notations[0]],
                            exclusions=d.exclusions)
    assert stats_hash.empreinte(ordre_a, 2) == stats_hash.empreinte(ordre_b, 2)


def test_replace_ne_piege_pas_le_hash():
    """`replace` sur une dataclass gelée produit une entrée équivalente — même hash."""
    d = _donnees()
    clone = DonneesExamen(
        criteres=d.criteres,
        notations=[replace(d.notations[0])],
        exclusions=d.exclusions,
    )
    assert stats_hash.empreinte(d, 1) == stats_hash.empreinte(clone, 1)
