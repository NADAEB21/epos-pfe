"""La sévérité intra-station (#359, ADR-0021 D1/D2) — exemples manuels EXACTS.

Comme les quatre autres indices : la formule est vérifiable à la main
(écart = moyenne(évaluateur) − moyenne(autres)), le contrat de refus est un
texte exact, et le défaut planté de la cohorte F1 (« un évaluateur décalé de
+2 intra-station ») doit être RETROUVÉ à la valeur attendue.
"""

from app.stats.engine import (
    REFUS_SEUL_EVALUATEUR, SEUIL_N_SEVERITE, severite_evaluateur,
)


def test_ecart_plante_de_plus_2_retrouve_exactement():
    """Le défaut F1 : un évaluateur qui note +2 au-dessus de ses pairs, même
    station. Valeurs constantes → écart exact, calculable de tête."""
    indice = severite_evaluateur(
        totaux_evaluateur=[12.0] * 10,
        totaux_autres=[10.0] * 10,
    )
    assert indice.statut == "CONCLUANT"
    assert indice.valeur == 2.0
    assert indice.n == 10
    assert indice.details["n_autres"] == 10
    assert indice.details["moyenne_evaluateur"] == 12.0
    assert indice.details["moyenne_autres"] == 10.0


def test_ecart_negatif_indulgence_aussi_visible():
    # Dérivé au tableur : moyenne éval = (8+9+10+9+8+9+10+9+8+10)/10 = 9,0 ;
    # moyenne autres = (11+12+11+12+11+12+11+12+11+12)/10 = 11,5 ; écart = −2,5.
    indice = severite_evaluateur(
        totaux_evaluateur=[8, 9, 10, 9, 8, 9, 10, 9, 8, 10],
        totaux_autres=[11, 12, 11, 12, 11, 12, 11, 12, 11, 12],
    )
    assert indice.statut == "CONCLUANT"
    assert indice.valeur == -2.5
    # L'IC bootstrap encadre l'écart observé (graine fixe : reproductible).
    assert indice.ic is not None
    assert indice.ic[0] <= -2.5 <= indice.ic[1]


def test_effectif_evaluateur_insuffisant_refus_d2():
    indice = severite_evaluateur(
        totaux_evaluateur=[12.0] * (SEUIL_N_SEVERITE - 1),
        totaux_autres=[10.0] * 20,
    )
    assert indice.statut == "NON_CONCLUANT"
    assert indice.valeur is None
    # Les mots d'ADR-0021 D2, avec le détail chiffré maison.
    assert indice.raison.startswith("comparaison non concluante — effectif insuffisant")
    assert f"n={SEUIL_N_SEVERITE - 1}" in indice.raison


def test_effectif_des_autres_insuffisant_refus_aussi():
    """La comparaison exige les DEUX effectifs — un pool d'en face trop maigre
    rendrait l'écart aussi peu défendable que l'inverse."""
    indice = severite_evaluateur(
        totaux_evaluateur=[12.0] * 20,
        totaux_autres=[10.0] * (SEUIL_N_SEVERITE - 1),
    )
    assert indice.statut == "NON_CONCLUANT"
    assert "comparaison non concluante" in indice.raison


def test_refus_seul_evaluateur_texte_exact():
    # Le gabarit est un contrat d'interface (D6) — le runner l'émet pour une
    # station à évaluateur unique ; on fige le texte ici.
    assert REFUS_SEUL_EVALUATEUR == (
        "comparaison non concluante — un seul évaluateur a noté cette station"
    )
