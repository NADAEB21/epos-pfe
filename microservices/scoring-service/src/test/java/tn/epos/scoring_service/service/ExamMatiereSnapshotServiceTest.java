package tn.epos.scoring_service.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import tn.epos.common.exception.BusinessException;
import tn.epos.scoring_service.client.ExamServiceClient;
import tn.epos.scoring_service.entities.ExamMatiereSnapshot;
import tn.epos.scoring_service.repositories.ExamMatiereSnapshotRepository;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * #274 / ADR-0015 — la matière d'un examen est figée localement, une fois.
 *
 * <p>Deux classes coopèrent et sont testées ensemble ici : {@link ExamMatiereSnapshotService}
 * (lecture locale, sinon délégation) et {@link ExamMatiereMaterialiser} (l'écriture, dans son
 * propre bean pour que {@code REQUIRES_NEW} traverse le proxy Spring).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Matière figée d'un examen (#274, ADR-0015)")
class ExamMatiereSnapshotServiceTest {

    private static final Long EXAMEN = 42L;
    private static final Long MATIERE = 7L;

    @Nested
    @DisplayName("Résolution")
    class Resolution {

        @Mock private ExamMatiereSnapshotRepository repository;
        @Mock private ExamMatiereMaterialiser materialiser;

        @InjectMocks private ExamMatiereSnapshotService service;

        /**
         * LE test qui justifie tout le dispositif : une fois la matière figée, l'autorisation ne
         * fait plus AUCUN appel réseau. C'est ce qui la rend survivable à une panne
         * d'exam-service le jour de l'épreuve (ADR-0015).
         */
        @Test
        @DisplayName("Matière déjà figée → lecture locale, ZÉRO appel à exam-service")
        void dejaFigee_aucunAppelReseau() {
            when(repository.findByExamenId(EXAMEN)).thenReturn(Optional.of(
                    ExamMatiereSnapshot.builder().examenId(EXAMEN).matiereId(MATIERE).build()));

            assertThat(service.resolveMatiereId(EXAMEN)).isEqualTo(MATIERE);

            verifyNoInteractions(materialiser);
        }

        @Test
        @DisplayName("Matière pas encore figée → délègue la matérialisation")
        void pasEncoreFigee_materialise() {
            when(repository.findByExamenId(EXAMEN)).thenReturn(Optional.empty());
            when(materialiser.materialiseMatiere(EXAMEN)).thenReturn(
                    ExamMatiereSnapshot.builder().examenId(EXAMEN).matiereId(MATIERE).build());

            assertThat(service.resolveMatiereId(EXAMEN)).isEqualTo(MATIERE);

            verify(materialiser).materialiseMatiere(EXAMEN);
        }

        @Test
        @DisplayName("examenId null → refus, sans toucher la base")
        void examenNull_refuse() {
            assertThatThrownBy(() -> service.resolveMatiereId(null))
                    .isInstanceOf(BusinessException.class);

            verifyNoInteractions(repository);
            verifyNoInteractions(materialiser);
        }
    }

    @Nested
    @DisplayName("Matérialisation")
    class Materialisation {

        @Mock private ExamServiceClient examServiceClient;
        @Mock private ExamMatiereSnapshotRepository repository;

        @InjectMocks private ExamMatiereMaterialiser materialiser;

        @Test
        @DisplayName("Fige la matière renvoyée par exam-service")
        void figeLaMatiere() {
            when(examServiceClient.getMatiereIdStrict(EXAMEN)).thenReturn(MATIERE);
            when(repository.save(any(ExamMatiereSnapshot.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            ExamMatiereSnapshot saved = materialiser.materialiseMatiere(EXAMEN);

            assertThat(saved.getExamenId()).isEqualTo(EXAMEN);
            assertThat(saved.getMatiereId()).isEqualTo(MATIERE);
        }

        /**
         * ⚠️ La règle la plus importante de cette classe : ne JAMAIS figer une valeur de repli.
         * Une matière devinée autoriserait durablement le mauvais responsable — le dégât serait
         * permanent, contrairement à un refus.
         */
        @Test
        @DisplayName("exam-service muet → RIEN n'est écrit et l'appel échoue")
        void examServiceMuet_nEcritRien() {
            when(examServiceClient.getMatiereIdStrict(EXAMEN))
                    .thenThrow(new BusinessException("exam-service injoignable"));

            assertThatThrownBy(() -> materialiser.materialiseMatiere(EXAMEN))
                    .isInstanceOf(BusinessException.class);

            verify(repository, never()).save(any(ExamMatiereSnapshot.class));
        }

        /**
         * Deux écritures concurrentes sur le même examen : la contrainte UNIQUE tranche, le
         * perdant relit le gagnant. Les deux valeurs sont identiques — la matière d'un examen
         * ne change pas — donc la course n'a aucune conséquence observable.
         */
        @Test
        @DisplayName("Course concurrente → le perdant relit le gagnant au lieu d'échouer")
        void course_relitLeGagnant() {
            when(examServiceClient.getMatiereIdStrict(EXAMEN)).thenReturn(MATIERE);
            when(repository.save(any(ExamMatiereSnapshot.class)))
                    .thenThrow(new DataIntegrityViolationException("uq_matiere_snapshot_examen"));
            when(repository.findByExamenId(EXAMEN)).thenReturn(Optional.of(
                    ExamMatiereSnapshot.builder().examenId(EXAMEN).matiereId(MATIERE).build()));

            assertThat(materialiser.materialiseMatiere(EXAMEN).getMatiereId()).isEqualTo(MATIERE);
        }

        @Test
        @DisplayName("Course + relecture vide (anomalie réelle) → l'erreur d'origine remonte")
        void course_relectureVide_remonteLErreur() {
            when(examServiceClient.getMatiereIdStrict(anyLong())).thenReturn(MATIERE);
            when(repository.save(any(ExamMatiereSnapshot.class)))
                    .thenThrow(new DataIntegrityViolationException("contrainte"));
            when(repository.findByExamenId(EXAMEN)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> materialiser.materialiseMatiere(EXAMEN))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }
}
