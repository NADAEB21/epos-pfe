package tn.epos.auth_service.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tn.epos.auth_service.entity.Matiere;
import tn.epos.auth_service.repository.MatiereRepository;
import tn.epos.common.dto.ApiResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * #303 — le corps publié aux pairs : les matières retirées SEULEMENT, id + libellé (le refus
 * d'exam-service doit être nominatif). La garde HMAC de la route est celle de
 * {@code InternalAuthFilterTest} — générique à {@code /internal/**}, pas re-testée ici.
 */
@ExtendWith(MockitoExtension.class)
class InternalMatiereControllerTest {

    @Mock
    private MatiereRepository matiereRepository;

    @InjectMocks
    private InternalMatiereController controller;

    @Test
    @DisplayName("publie id + libellé des matières retirées, rien d'autre")
    void publieLesRetirees() {
        Matiere retiree = Matiere.builder().id(10L).code("PHARMA")
                .libelle("Pharmacognosie").active(false).build();
        when(matiereRepository.findByActiveFalse()).thenReturn(List.of(retiree));

        ApiResponse<List<InternalMatiereController.RetiredMatiereEntry>> body =
                controller.matieresRetirees().getBody();

        assertThat(body).isNotNull();
        assertThat(body.getData())
                .containsExactly(new InternalMatiereController.RetiredMatiereEntry(10L, "Pharmacognosie"));
    }

    @Test
    @DisplayName("catalogue entièrement actif → liste vide (jamais null)")
    void catalogueActifListeVide() {
        when(matiereRepository.findByActiveFalse()).thenReturn(List.of());

        ApiResponse<List<InternalMatiereController.RetiredMatiereEntry>> body =
                controller.matieresRetirees().getBody();

        assertThat(body).isNotNull();
        assertThat(body.getData()).isEmpty();
    }
}
