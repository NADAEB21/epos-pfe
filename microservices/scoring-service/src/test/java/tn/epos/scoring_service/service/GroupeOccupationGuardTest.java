package tn.epos.scoring_service.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tn.epos.common.exception.BusinessException;
import tn.epos.scoring_service.entities.Rotation;
import tn.epos.scoring_service.entities.RotationStatus;
import tn.epos.scoring_service.entities.StudentGroup;
import tn.epos.scoring_service.repositories.IRotationRepository;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * #431 — « un groupe n'est qu'à une station à la fois ». Le discriminant est la station :
 * une rotation EN_COURS du même groupe à la MÊME station (reprise) ne compte pas, une
 * rotation TERMINE ailleurs non plus. Seule une rotation EN_COURS à une AUTRE station refuse.
 */
@ExtendWith(MockitoExtension.class)
class GroupeOccupationGuardTest {

    private static final Long GROUPE = 12L;
    private static final Long STATION_A = 1L;
    private static final Long STATION_B = 2L;

    @Mock private IRotationRepository rotationRepository;
    @InjectMocks private GroupeOccupationGuard guard;

    private Rotation rotation(long id, Long stationId, RotationStatus statut) {
        Rotation r = new Rotation();
        r.setId(id);
        r.setStationId(stationId);
        r.setStatut(statut);
        StudentGroup g = new StudentGroup();
        g.setId(GROUPE);
        g.setNumeroGroupe(2);
        r.setStudentGroup(g);
        return r;
    }

    @Test
    @DisplayName("groupe EN_COURS à une autre station → refus qui nomme le groupe")
    void refuse_siEnCoursAilleurs() {
        Rotation cible = rotation(10L, STATION_A, RotationStatus.EN_ATTENTE);
        Rotation ailleurs = rotation(11L, STATION_B, RotationStatus.EN_COURS);
        when(rotationRepository.findByStudentGroupId(GROUPE)).thenReturn(List.of(cible, ailleurs));

        assertThat(guard.enCoursAilleurs(cible)).contains(ailleurs);
        assertThatThrownBy(() -> guard.refuserSiOccupeAilleurs(cible))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Le groupe 2")
                .hasMessageContaining("autre station");
    }

    @Test
    @DisplayName("groupe TERMINE ailleurs → libre")
    void passe_siTermineAilleurs() {
        Rotation cible = rotation(10L, STATION_A, RotationStatus.EN_ATTENTE);
        Rotation ailleurs = rotation(11L, STATION_B, RotationStatus.TERMINE);
        when(rotationRepository.findByStudentGroupId(GROUPE)).thenReturn(List.of(cible, ailleurs));

        assertThat(guard.enCoursAilleurs(cible)).isEmpty();
        assertThatCode(() -> guard.refuserSiOccupeAilleurs(cible)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("EN_COURS à la MÊME station (reprise) → libre — le discriminant est la station")
    void passe_siEnCoursMemeStation() {
        Rotation cible = rotation(10L, STATION_A, RotationStatus.EN_COURS);
        when(rotationRepository.findByStudentGroupId(GROUPE)).thenReturn(List.of(cible));

        assertThat(guard.enCoursAilleurs(cible)).isEmpty();
    }

    @Test
    @DisplayName("rotation sans groupe (donnée ancienne) → libre, aucune requête")
    void passe_sansGroupe() {
        Rotation cible = new Rotation();
        cible.setId(10L);
        cible.setStationId(STATION_A);

        assertThat(guard.enCoursAilleurs(cible)).isEmpty();
    }
}
