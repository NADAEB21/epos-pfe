package tn.epos.scoring_service.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tn.epos.scoring_service.entities.Lot;
import tn.epos.scoring_service.entities.LotStatus;
import tn.epos.scoring_service.repositories.ILotRepository;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("LotService - Tests unitaires")
class LotServiceTest {

    @Mock
    private ILotRepository lotRepository;

    /** #274 — permissif ici : le perimetre de matiere a ses propres tests. */
    @Mock private MatiereAccessGuard matiereAccessGuard;

    @InjectMocks
    private LotService lotService;

    private Lot lot;

    @BeforeEach
    void setUp() {
        // Lot : id, examenId, evaluateurId, numeroLot, tailleLot, statut (LotStatus enum)
        lot = new Lot();
        lot.setId(1L);
        lot.setExamenId(10L);
        lot.setEvaluateurId(5L);
        lot.setNumeroLot(1);
        lot.setTailleLot(20);
        lot.setStatut(LotStatus.EN_ATTENTE);
    }

    @Nested
    @DisplayName("findAll()")
    class FindAll {

        @Test
        @DisplayName("Doit retourner tous les lots")
        void findAll_devraitRetournerListe() {
            when(lotRepository.findAll()).thenReturn(List.of(lot));

            List<Lot> result = lotService.findAll();

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getNumeroLot()).isEqualTo(1);
            assertThat(result.get(0).getStatut()).isEqualTo(LotStatus.EN_ATTENTE);
            verify(lotRepository, times(1)).findAll();
        }

        @Test
        @DisplayName("Doit retourner une liste vide si aucun lot")
        void findAll_devraitRetournerListeVide() {
            when(lotRepository.findAll()).thenReturn(List.of());

            List<Lot> result = lotService.findAll();

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("findById()")
    class FindById {

        @Test
        @DisplayName("Doit retourner le lot si trouvé")
        void findById_devraitRetournerLot() {
            when(lotRepository.findById(1L)).thenReturn(Optional.of(lot));

            Optional<Lot> result = lotService.findById(1L);

            assertThat(result).isPresent();
            assertThat(result.get().getId()).isEqualTo(1L);
            assertThat(result.get().getExamenId()).isEqualTo(10L);
        }

        @Test
        @DisplayName("Doit retourner Optional vide si introuvable")
        void findById_devraitRetournerVideSiIntrouvable() {
            when(lotRepository.findById(99L)).thenReturn(Optional.empty());

            Optional<Lot> result = lotService.findById(99L);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("save()")
    class Save {

        @Test
        @DisplayName("Doit sauvegarder et retourner le lot")
        void save_devraitSauvegarder() {
            when(lotRepository.save(any(Lot.class))).thenReturn(lot);

            Lot result = lotService.save(lot);

            assertThat(result).isNotNull();
            assertThat(result.getTailleLot()).isEqualTo(20);
            assertThat(result.getEvaluateurId()).isEqualTo(5L);
            verify(lotRepository, times(1)).save(any(Lot.class));
        }
    }

    @Nested
    @DisplayName("delete()")
    class Delete {

        /**
         * #274 — la suppression CHARGE désormais le lot avant de le supprimer. Ce n'est pas un
         * détour gratuit : {@code deleteById} ne révèle pas à quel examen le lot appartenait,
         * donc aucun périmètre de matière n'était vérifiable. Le test suit ce contrat.
         */
        @Test
        @DisplayName("#274 — charge le lot, vérifie le périmètre, PUIS supprime")
        void delete_devraitVerifierLePerimetrePuisSupprimer() {
            Lot lot = new Lot();
            lot.setId(1L);
            lot.setExamenId(42L);
            when(lotRepository.findById(1L)).thenReturn(Optional.of(lot));

            lotService.delete(1L);

            verify(matiereAccessGuard).checkExamenAccess(42L);
            verify(lotRepository, times(1)).delete(lot);
        }

        @Test
        @DisplayName("Un lot inconnu ne supprime rien et ne lève pas")
        void delete_lotInconnu_neSupprimeRien() {
            when(lotRepository.findById(404L)).thenReturn(Optional.empty());

            lotService.delete(404L);

            verify(lotRepository, never()).delete(any(Lot.class));
        }
    }

    @Nested
    @DisplayName("update()")
    class Update {

        @Test
        @DisplayName("Doit mettre à jour tous les champs du lot si trouvé")
        void update_devraitMettreAJourLot() {
            Lot details = new Lot();
            details.setNumeroLot(2);
            details.setTailleLot(30);
            details.setStatut(LotStatus.EN_COURS);
            details.setEvaluateurId(7L);
            details.setExamenId(12L);

            when(lotRepository.findById(1L)).thenReturn(Optional.of(lot));
            when(lotRepository.save(any(Lot.class))).thenAnswer(inv -> inv.getArgument(0));

            Lot result = lotService.update(1L, details);

            assertThat(result.getNumeroLot()).isEqualTo(2);
            assertThat(result.getTailleLot()).isEqualTo(30);
            assertThat(result.getStatut()).isEqualTo(LotStatus.EN_COURS);
            assertThat(result.getEvaluateurId()).isEqualTo(7L);
            assertThat(result.getExamenId()).isEqualTo(12L);
            verify(lotRepository).save(any(Lot.class));
        }

        @Test
        @DisplayName("Transition statut EN_ATTENTE → TERMINE doit réussir")
        void update_devraitAccepterTransitionStatutTermine() {
            Lot details = new Lot();
            details.setNumeroLot(lot.getNumeroLot());
            details.setTailleLot(lot.getTailleLot());
            details.setStatut(LotStatus.TERMINE);
            details.setEvaluateurId(lot.getEvaluateurId());
            details.setExamenId(lot.getExamenId());

            when(lotRepository.findById(1L)).thenReturn(Optional.of(lot));
            when(lotRepository.save(any(Lot.class))).thenAnswer(inv -> inv.getArgument(0));

            Lot result = lotService.update(1L, details);

            assertThat(result.getStatut()).isEqualTo(LotStatus.TERMINE);
        }

        @Test
        @DisplayName("Doit lever RuntimeException si lot introuvable")
        void update_devraitLeverExceptionSiIntrouvable() {
            when(lotRepository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> lotService.update(99L, new Lot()))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("99");
        }

        @Test
        @DisplayName("#215 : un PUT partiel (sans examenId/evaluateurId) ne doit PAS null-ifier les FK")
        void update_putPartiel_preserveLesFk() {
            Lot details = new Lot();
            details.setNumeroLot(2);
            details.setTailleLot(30);
            details.setStatut(LotStatus.EN_COURS);
            // examenId / evaluateurId NON fournis (comme le contrôleur sur PUT)

            when(lotRepository.findById(1L)).thenReturn(Optional.of(lot));
            when(lotRepository.save(any(Lot.class))).thenAnswer(inv -> inv.getArgument(0));

            Lot result = lotService.update(1L, details);

            assertThat(result.getNumeroLot()).isEqualTo(2);
            assertThat(result.getExamenId()).isEqualTo(10L);     // préservé
            assertThat(result.getEvaluateurId()).isEqualTo(5L);  // préservé
        }
    }
}
