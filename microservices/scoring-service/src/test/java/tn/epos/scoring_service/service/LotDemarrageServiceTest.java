package tn.epos.scoring_service.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tn.epos.common.exception.BusinessException;
import tn.epos.scoring_service.dto.DemarrageResult;
import tn.epos.scoring_service.dto.GenerationResult;
import tn.epos.scoring_service.dto.PresenceResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

/**
 * #185 — « Présence & démarrer » : un seul acte, dans le bon ordre, qui échoue en bloc.
 *
 * <p>Ce service ne DÉCIDE rien : il soude {@code markPresence} puis {@code generateForLot}
 * (l'ouverture de vague reste déléguée à ADR-0014-B, à l'intérieur de la génération).
 * Les tests épinglent donc l'ORDRE, la PROPAGATION des refus (transaction : pas d'état
 * « présence prise, pas de circuit »), et la fidélité du résultat combiné.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("LotDemarrageService — présence + génération en un acte (#185)")
class LotDemarrageServiceTest {

    private static final Long LOT = 40L;

    @Mock private LotAssignmentService      lotAssignmentService;
    @Mock private RotationGenerationService rotationGenerationService;

    @InjectMocks private LotDemarrageService service;

    @Test
    @DisplayName("présence PUIS génération, résultat combiné fidèle")
    void demarre_presencePuisGeneration() {
        when(lotAssignmentService.markPresence(LOT, List.of(9L)))
                .thenReturn(new PresenceResult(LOT, 4, 3, 1));
        when(rotationGenerationService.generateForLot(LOT))
                .thenReturn(new GenerationResult(1, 2, 2, 2, 4, 6, 3, 1, null));

        DemarrageResult r = service.presenceEtDemarrer(LOT, List.of(9L));

        // L'ordre est le contrat : la génération se construit sur la présence.
        var ordre = inOrder(lotAssignmentService, rotationGenerationService);
        ordre.verify(lotAssignmentService).markPresence(LOT, List.of(9L));
        ordre.verify(rotationGenerationService).generateForLot(LOT);

        assertThat(r.presents()).isEqualTo(3);
        assertThat(r.absents()).isEqualTo(1);
        assertThat(r.rotations()).isEqualTo(4);
        assertThat(r.assignments()).isEqualTo(6);
        assertThat(r.avertissement()).isNull();
    }

    /**
     * Le refus de la génération (garde anti-perte #188, examen non lancé…) remonte TEL QUEL —
     * il est déjà bruyant et nominatif — et, ce service étant transactionnel, la présence
     * écrite à l'étape 1 est annulée avec lui : l'utilisateur ne peut pas se retrouver dans
     * l'état intermédiaire « présence prise, pas de circuit » qu'il faudrait comprendre.
     */
    @Test
    @DisplayName("refus de génération → propagé tel quel (et la transaction annule la présence)")
    void demarre_propageLesRefusDeGeneration() {
        when(lotAssignmentService.markPresence(any(), any()))
                .thenReturn(new PresenceResult(LOT, 4, 4, 0));
        when(rotationGenerationService.generateForLot(LOT))
                .thenThrow(new BusinessException(
                        "Régénération impossible : 3 notation(s) ont déjà été saisies pour le lot 1."));

        assertThatThrownBy(() -> service.presenceEtDemarrer(LOT, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("notation(s) ont déjà été saisies");
    }

    @Test
    @DisplayName("l'avertissement de capacité de la génération traverse jusqu'au conducteur")
    void demarre_remonteLAvertissement() {
        when(lotAssignmentService.markPresence(any(), any()))
                .thenReturn(new PresenceResult(LOT, 9, 9, 0));
        when(rotationGenerationService.generateForLot(LOT))
                .thenReturn(new GenerationResult(1, 2, 2, 2, 4, 18, 9, 0,
                        "Capacité dépassée : 5 étudiants/station"));

        DemarrageResult r = service.presenceEtDemarrer(LOT, null);

        assertThat(r.avertissement()).contains("Capacité dépassée");
    }
}
