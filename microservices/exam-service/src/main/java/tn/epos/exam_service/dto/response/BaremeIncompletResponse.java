package tn.epos.exam_service.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * #276 — une station dont le barème ne permet PAS d'atteindre la note annoncée.
 *
 * <p>Sert la ligne bloquante « Barèmes complets » du pre-flight de Lancement
 * (doctrine #185 : la pré-condition s'affiche AVANT le clic ; la garde
 * autoritaire reste {@code changerStatut}). Même forme que
 * {@link ConflitEvaluateurResponse}, pour la même raison.
 *
 * <p>Les deux champs sont renvoyés séparément parce que c'est la phrase que le
 * responsable doit lire : « 10 points saisis sur 20 » se corrige en une minute,
 * « barème invalide » envoie chercher.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BaremeIncompletResponse {
    private Long stationId;
    private String stationNom;
    private Long grilleId;
    /** La note annoncée pour la station. */
    private Double noteMax;
    /** Ce qu'un étudiant sans-faute peut réellement obtenir. */
    private Double maxAtteignable;
}
