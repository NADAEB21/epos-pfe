package tn.epos.scoring_service.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Une convocation : ce qu'un étudiant doit savoir avant l'examen.
 *
 * <p><b>Contenu volontairement limité au lot + au jour + à l'heure de
 * convocation</b> (ADR-0014-A §4/§6). Pas de salle : le champ « lieu » a été
 * examiné puis écarté faute de consommateur, la convocation ne le portant pas.
 * Pas d'ordre de passage par station non plus — il est attribué sur place, après
 * l'appel, donc le promettre ici serait mentir.
 *
 * <p><b>Dérivée côté serveur, et c'est le point.</b> L'heure d'arrivée était
 * calculée dans le composant Angular ; l'envoi par e-mail devait la calculer
 * aussi, ce qui aurait créé deux implémentations de la même règle métier dans
 * deux langages — la garantie d'une dérive silencieuse. Le web LIT désormais
 * cette dérivation au lieu de la refaire.
 */
public record ConvocationDTO(
    Long participationId,
    Long etudiantId,
    String nom,
    String prenom,
    String numero_inscription,
    String email,
    Integer ordre_import,
    Long lotId,
    Integer lotNumero,
    /** Le jour du lot de l'étudiant (multi-jours, #147), sinon la date de l'examen. */
    LocalDate jour,
    /** Heure de convocation de la vague, "HH:mm". */
    String heureConvocation,
    /** Quand sa convocation a été envoyée ; null = jamais. */
    LocalDateTime convocationEnvoyeeA
) {
    /** true dès qu'on peut réellement joindre l'étudiant. */
    public boolean joignable() {
        return email != null && !email.isBlank();
    }
}
