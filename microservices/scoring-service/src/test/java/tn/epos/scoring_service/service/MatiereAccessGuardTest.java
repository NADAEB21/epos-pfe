package tn.epos.scoring_service.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import tn.epos.common.exception.BusinessException;
import tn.epos.scoring_service.config.MatiereScopeChecker;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * #274 — la porte unique des écritures bornées par matière : résoudre PUIS vérifier.
 *
 * <p>Ce qui est testé ici n'est pas la décision (elle appartient à {@code MatiereScopeChecker})
 * mais le <b>câblage</b> : que la résolution soit bien suivie d'une vérification, et que les
 * échecs se propagent au lieu d'ouvrir la porte.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MatiereAccessGuard — résolution + vérification (#274)")
class MatiereAccessGuardTest {

    @Mock private ExamMatiereSnapshotService matiereSnapshot;
    @Mock private MatiereScopeChecker matiereScopeChecker;

    @InjectMocks private MatiereAccessGuard guard;

    @Test
    @DisplayName("Résout la matière de l'examen, puis la soumet au contrôle")
    void resoutPuisVerifie() {
        when(matiereSnapshot.resolveMatiereId(42L)).thenReturn(7L);

        assertThatCode(() -> guard.checkExamenAccess(42L)).doesNotThrowAnyException();

        verify(matiereSnapshot).resolveMatiereId(42L);
        verify(matiereScopeChecker).checkAccess(7L);
    }

    @Test
    @DisplayName("Un refus de périmètre remonte tel quel (403)")
    void refusRemonte() {
        when(matiereSnapshot.resolveMatiereId(42L)).thenReturn(7L);
        doThrow(new AccessDeniedException("hors périmètre"))
                .when(matiereScopeChecker).checkAccess(7L);

        assertThatThrownBy(() -> guard.checkExamenAccess(42L))
                .isInstanceOf(AccessDeniedException.class);
    }

    /**
     * Le cas D7 de l'arbre de décision : exam-service injoignable ET matière jamais figée.
     * L'écriture doit être REFUSÉE bruyamment. Refuser est réversible ; autoriser une écriture
     * hors périmètre en pleine épreuve ne l'est pas.
     */
    @Test
    @DisplayName("Matière non figée + exam-service injoignable → refus bruyant, pas d'ouverture")
    void matiereIrresolvable_refuseBruyamment() {
        when(matiereSnapshot.resolveMatiereId(42L))
                .thenThrow(new BusinessException("exam-service injoignable"));

        assertThatThrownBy(() -> guard.checkExamenAccess(42L))
                .isInstanceOf(BusinessException.class);

        verify(matiereScopeChecker, never()).checkAccess(anyLong());
    }

    /**
     * Chaîne d'entités rompue (lot détaché, notation sans affectation). On refuse AVANT de tenter
     * la résolution : inutile d'interroger quoi que ce soit, et le message reste lisible.
     */
    @Test
    @DisplayName("examenId null → 403 immédiat, sans même tenter la résolution")
    void examenNull_refuseSansResoudre() {
        assertThatThrownBy(() -> guard.checkExamenAccess(null))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("examen de rattachement");

        verifyNoInteractions(matiereSnapshot);
        verifyNoInteractions(matiereScopeChecker);
    }
}
