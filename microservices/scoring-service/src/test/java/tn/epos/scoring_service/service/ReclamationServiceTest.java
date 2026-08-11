package tn.epos.scoring_service.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import tn.epos.common.exception.BusinessException;
import tn.epos.common.exception.ResourceNotFoundException;
import tn.epos.scoring_service.config.EvaluateurScopeChecker;
import tn.epos.scoring_service.dto.ReclamationDTO;
import tn.epos.scoring_service.dto.ReclamationRequest;
import tn.epos.scoring_service.dto.ReclamationResolveRequest;
import tn.epos.scoring_service.entities.Reclamation;
import tn.epos.scoring_service.entities.ReclamationStatus;
import tn.epos.scoring_service.repositories.IExamenParticipationRepository;
import tn.epos.scoring_service.repositories.IReclamationRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReclamationService — registre des réclamations étudiantes (#136)")
class ReclamationServiceTest {

    private static final Long RESP_USER_ID = 77L;

    @Mock private IReclamationRepository reclamationRepository;
    @Mock private IExamenParticipationRepository participationRepository;
    @Mock private EvaluateurScopeChecker scopeChecker;

    /** #274 — permissif ici : le perimetre de matiere a ses propres tests. */
    @Mock private MatiereAccessGuard matiereAccessGuard;

    @InjectMocks
    private ReclamationService service;

    private ReclamationRequest req;

    @BeforeEach
    void setUp() {
        req = new ReclamationRequest(9L, 3L, 5L, "L'étudiant conteste la note de la station 2");
    }

    @Nested
    @DisplayName("creer()")
    class Creer {

        @Test
        @DisplayName("Enregistre en EN_ATTENTE, attribue à l'appelant, sans réponse ni résolution")
        void creer_enregistreEnAttente() {
            when(scopeChecker.getCallerUserId()).thenReturn(RESP_USER_ID);
            when(participationRepository.existsById(3L)).thenReturn(true);
            when(reclamationRepository.save(any(Reclamation.class))).thenAnswer(inv -> {
                Reclamation r = inv.getArgument(0);
                r.setId(1L);
                return r;
            });

            ReclamationDTO dto = service.creer(req);

            ArgumentCaptor<Reclamation> captor = ArgumentCaptor.forClass(Reclamation.class);
            verify(reclamationRepository).save(captor.capture());
            Reclamation saved = captor.getValue();
            assertThat(saved.getExamenId()).isEqualTo(9L);
            assertThat(saved.getParticipationId()).isEqualTo(3L);
            assertThat(saved.getNotationId()).isEqualTo(5L);
            assertThat(saved.getObjet()).isEqualTo("L'étudiant conteste la note de la station 2");
            assertThat(saved.getStatut()).isEqualTo(ReclamationStatus.EN_ATTENTE);
            assertThat(saved.getCreatedByUserId()).isEqualTo(RESP_USER_ID);
            assertThat(saved.getReponse()).isNull();
            assertThat(saved.getResolvedByUserId()).isNull();
            assertThat(saved.getResolvedAt()).isNull();

            assertThat(dto.statut()).isEqualTo(ReclamationStatus.EN_ATTENTE);
        }

        @Test
        @DisplayName("Participation introuvable → ResourceNotFoundException, rien n'est enregistré")
        void creer_participationIntrouvable_leve() {
            when(scopeChecker.getCallerUserId()).thenReturn(RESP_USER_ID);
            when(participationRepository.existsById(3L)).thenReturn(false);

            assertThatThrownBy(() -> service.creer(req))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("3");

            verify(reclamationRepository, never()).save(any());
        }

        @Test
        @DisplayName("Identité appelant introuvable → AccessDeniedException, aucune écriture")
        void creer_callerInconnu_refuse() {
            when(scopeChecker.getCallerUserId()).thenReturn(null);

            assertThatThrownBy(() -> service.creer(req))
                    .isInstanceOf(AccessDeniedException.class);

            verify(participationRepository, never()).existsById(any());
            verify(reclamationRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("resoudre()")
    class Resoudre {

        private Reclamation pending() {
            Reclamation r = Reclamation.builder()
                    .id(1L).examenId(9L).participationId(3L).notationId(5L)
                    .objet("conteste").statut(ReclamationStatus.EN_ATTENTE)
                    .createdByUserId(RESP_USER_ID).createdAt(LocalDateTime.now())
                    .build();
            return r;
        }

        @Test
        @DisplayName("ACCEPTEE : fixe statut, réponse, lien d'ajustement, qui/quand la décide")
        void resoudre_acceptee() {
            when(scopeChecker.getCallerUserId()).thenReturn(RESP_USER_ID);
            Reclamation r = pending();
            when(reclamationRepository.findById(1L)).thenReturn(Optional.of(r));
            when(reclamationRepository.save(any(Reclamation.class))).thenAnswer(inv -> inv.getArgument(0));

            ReclamationResolveRequest resolve = new ReclamationResolveRequest(
                    ReclamationStatus.ACCEPTEE, "Erreur de saisie confirmée, note corrigée", 42L);

            ReclamationDTO dto = service.resoudre(1L, resolve);

            assertThat(r.getStatut()).isEqualTo(ReclamationStatus.ACCEPTEE);
            assertThat(r.getReponse()).isEqualTo("Erreur de saisie confirmée, note corrigée");
            assertThat(r.getAdjustmentId()).isEqualTo(42L);
            assertThat(r.getResolvedByUserId()).isEqualTo(RESP_USER_ID);
            assertThat(r.getResolvedAt()).isNotNull();
            assertThat(dto.statut()).isEqualTo(ReclamationStatus.ACCEPTEE);
        }

        @Test
        @DisplayName("REJETEE : trace le rejet SANS ajustement — le cas que l'audit de réajustement seul ne pouvait capturer")
        void resoudre_rejetee_sansAjustement() {
            when(scopeChecker.getCallerUserId()).thenReturn(RESP_USER_ID);
            Reclamation r = pending();
            when(reclamationRepository.findById(1L)).thenReturn(Optional.of(r));
            when(reclamationRepository.save(any(Reclamation.class))).thenAnswer(inv -> inv.getArgument(0));

            ReclamationResolveRequest resolve = new ReclamationResolveRequest(
                    ReclamationStatus.REJETEE, "Note conforme au barème, réclamation infondée", null);

            service.resoudre(1L, resolve);

            assertThat(r.getStatut()).isEqualTo(ReclamationStatus.REJETEE);
            assertThat(r.getReponse()).isEqualTo("Note conforme au barème, réclamation infondée");
            assertThat(r.getAdjustmentId()).isNull();
            assertThat(r.getResolvedByUserId()).isEqualTo(RESP_USER_ID);
        }

        @Test
        @DisplayName("Décision EN_ATTENTE refusée → BusinessException (400), la réclamation reste intacte")
        void resoudre_versEnAttente_refuse() {
            when(scopeChecker.getCallerUserId()).thenReturn(RESP_USER_ID);

            ReclamationResolveRequest resolve = new ReclamationResolveRequest(
                    ReclamationStatus.EN_ATTENTE, "x", null);

            assertThatThrownBy(() -> service.resoudre(1L, resolve))
                    .isInstanceOf(BusinessException.class);

            verify(reclamationRepository, never()).findById(any());
            verify(reclamationRepository, never()).save(any());
        }

        @Test
        @DisplayName("Réclamation déjà traitée → BusinessException (400), pas de ré-écriture")
        void resoudre_dejaTraitee_refuse() {
            when(scopeChecker.getCallerUserId()).thenReturn(RESP_USER_ID);
            Reclamation r = pending();
            r.setStatut(ReclamationStatus.REJETEE);
            when(reclamationRepository.findById(1L)).thenReturn(Optional.of(r));

            ReclamationResolveRequest resolve = new ReclamationResolveRequest(
                    ReclamationStatus.ACCEPTEE, "revirement", null);

            assertThatThrownBy(() -> service.resoudre(1L, resolve))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("déjà traitée");

            verify(reclamationRepository, never()).save(any());
        }

        @Test
        @DisplayName("Réclamation introuvable → ResourceNotFoundException")
        void resoudre_introuvable_leve() {
            when(scopeChecker.getCallerUserId()).thenReturn(RESP_USER_ID);
            when(reclamationRepository.findById(99L)).thenReturn(Optional.empty());

            ReclamationResolveRequest resolve = new ReclamationResolveRequest(
                    ReclamationStatus.ACCEPTEE, "x", null);

            assertThatThrownBy(() -> service.resoudre(99L, resolve))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("99");
        }
    }

    @Nested
    @DisplayName("lecture")
    class Lecture {

        @Test
        @DisplayName("listerParExamen() renvoie les réclamations mappées en DTO")
        void listerParExamen_mappeDTO() {
            Reclamation r = Reclamation.builder()
                    .id(1L).examenId(9L).participationId(3L).objet("conteste")
                    .statut(ReclamationStatus.EN_ATTENTE).createdByUserId(RESP_USER_ID)
                    .createdAt(LocalDateTime.now()).build();
            when(reclamationRepository.findByExamenIdOrderByCreatedAtDesc(9L)).thenReturn(List.of(r));

            List<ReclamationDTO> result = service.listerParExamen(9L);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).examenId()).isEqualTo(9L);
            assertThat(result.get(0).statut()).isEqualTo(ReclamationStatus.EN_ATTENTE);
        }

        @Test
        @DisplayName("trouver() introuvable → ResourceNotFoundException")
        void trouver_introuvable_leve() {
            when(reclamationRepository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.trouver(99L))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }
}
