package tn.epos.exam_service.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * #265 — un examen EN_COURS qui retient des évaluateurs dont CET examen a aussi
 * besoin. Sert la ligne « Évaluateurs disponibles » du pre-flight de Lancement
 * (doctrine #185 : la pré-condition s'affiche AVANT le clic ; la garde
 * autoritaire reste {@code changerStatut}).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConflitEvaluateurResponse {
    private Long examenId;
    private String examenNom;
    /** Les évaluateurs partagés (bindés dans les deux examens). */
    private List<Long> evaluateurIds;
}
