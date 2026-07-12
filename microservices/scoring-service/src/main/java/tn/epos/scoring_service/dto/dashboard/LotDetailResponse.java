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