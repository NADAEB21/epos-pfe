package tn.epos.scoring_service.dto.dashboard;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Détail d'un lot avec la liste complète des étudiants.
 *
 * Correspond à LotModel.fromJson() dans Flutter (grading_models.dart) :
 *   json['id']        → int
 *   json['numero']    → int     (numéro du lot dans la rotation, ex: 3)
 *   json['total']     → int     (nombre total de lots, ex: 8)
 *   json['valide']    → bool
 *   json['etudiants'] → List   (liste EtudiantModel)
 *
 * Chaque étudiant (EtudiantModel.fromJson) :
 *   json['id']                → int
 *   json['nom']               → String
 *   json['prenom']            → String
 *   json['numeroInscription'] → String
 *   json['numeroEchantillon'] → int?   (nullable, depuis ExamenParticipation)
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LotDetailResponse {

    private Long              id;
    private int               numero;
    private int               total;
    private boolean           valide;

    /**
     * #248 — reste-t-il un passage APRÈS celui-ci, à CETTE station et dans CE lot ?
     *
     * <p>C'est la seule réponse à « le bouton Groupe suivant doit-il être actif ? ».
     * Le client la calculait auparavant avec {@code numero >= total}, c'est-à-dire avec le
     * numéro du groupe courant : or le carré latin fait tourner les groupes, donc une station
     * peut recevoir le groupe 2 puis le groupe 1. La garde était alors vraie exactement à
     * l'envers — grisée au premier passage, active au dernier (où le clic effaçait l'écran).
     *
     * <p>Calculé sur {@code ordrePassage}, comme {@code validerGroupe} : une seule source de
     * vérité pour « quel est le passage suivant » (ADR-0014 : l'horloge ne séquence plus rien).
     */
    private boolean           groupeSuivantDisponible;

    /**
     * #209 — début RÉEL du passage : l'instant où l'évaluateur a ouvert ce groupe, horodaté
     * côté serveur (write-once). C'est l'ancre du compte à rebours PLANCHER du mobile —
     * {@code debutCreneau} est un horaire PLANIFIÉ et ne chronomètre plus rien (constaté :
     * « 12:51 » restants sur une station de 2 minutes). Rouge « +MM:SS » en dépassement :
     * un avertissement, jamais un blocage.
     */
    private java.time.LocalDateTime debutReel;

    private List<EtudiantLotResponse> etudiants;

    @Getter @Builder @NoArgsConstructor @AllArgsConstructor
    public static class EtudiantLotResponse {
        private Long    id;
        private String  nom;
        private String  prenom;
        private String  numeroInscription;
        private Integer numeroEchantillon;
        private boolean absent;
        private boolean verrouille; // ← nouveau
        private String  commentaire;                        // ← nouveau
        private List<NotationItemResponse> notationItems;  // ← nouveau
    }

    @Getter @Builder @NoArgsConstructor @AllArgsConstructor
    public static class NotationItemResponse {
        private Long  itemId;
        private Float valeur;
    }
}