package tn.epos.scoring_service.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tn.epos.scoring_service.entities.Etudiant;
import tn.epos.scoring_service.entities.ExamenParticipation;
import tn.epos.scoring_service.entities.Lot;
import tn.epos.scoring_service.entities.LotStatus;
import tn.epos.scoring_service.repositories.IExamenParticipationRepository;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ExamenParticipationService - Tests unitaires")
class ExamenParticipationServiceTest {

    @Mock
    private IExamenParticipationRepository repository;

    @InjectMocks
    private ExamenParticipationService service;

    private ExamenParticipation participation;
    private Etudiant etudiant;
    private Lot lot;

    @BeforeEach
    void setUp() {
        // ExamenParticipation : id, examen_id, num_echantillon, note, est_present, etudiant, lot
        etudiant = new Etudiant();
        etudiant.setId(1L);
        etudiant.setNom("Ben Ali");
        etudiant.setPrenom("Mohamed");

        lot = new Lot();
        lot.setId(1L);
        lot.setNumeroLot(1);
        lot.setStatut(LotStatus.EN_ATTENTE);

        participation = new ExamenParticipation();
        participation.setId(1L);
        participation.setExamen_id(10L);
        participation.setNum_echantillon("ECH-001");
        participation.setNote(15.5f);
        participation.setEst_present(true);
        participation.setEtudiant(etudiant);
        participation.setLot(lot);
    }

    @Nested
    @DisplayName("getAll()")
    class GetAll {

        @Test
        @DisplayName("Doit retourner toutes les participations")
        void getAll_devraitRetournerListe() {
            when(repository.findAll()).thenReturn(List.of(participation));

            List<ExamenParticipation> result = service.getAll();

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getExamen_id()).isEqualTo(10L);
            assertThat(result.get(0).getNum_echantillon()).isEqualTo("ECH-001");
            verify(repository, times(1)).findAll();
        }

        @Test
        @DisplayName("Doit retourner une liste vide si aucune participation")
        void getAll_devraitRetournerListeVide() {
            when(repository.findAll()).thenReturn(List.of());

            List<ExamenParticipation> result = service.getAll();

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("getById()")
    class GetById {

        @Test
        @DisplayName("Doit retourner la participation si trouvée")
        void getById_devraitRetournerParticipation() {
            when(repository.findById(1L)).thenReturn(Optional.of(participation));

            Optional<ExamenParticipation> result = service.getById(1L);

            assertThat(result).isPresent();
            assertThat(result.get().getId()).isEqualTo(1L);
            assertThat(result.get().getEst_present()).isTrue();
        }

        @Test
        @DisplayName("Doit retourner Optional vide si introuvable")
        void getById_devraitRetournerVideSiIntrouvable() {
            when(repository.findById(99L)).thenReturn(Optional.empty());

            Optional<ExamenParticipation> result = service.getById(99L);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("save()")
    class Save {

        @Test
        @DisplayName("Doit sauvegarder et retourner la participation")
        void save_devraitSauvegarder() {
            when(repository.save(any(ExamenParticipation.class))).thenReturn(participation);

            ExamenParticipation result = service.save(participation);

            assertThat(result).isNotNull();
            assertThat(result.getNote()).isEqualTo(15.5f);
            assertThat(result.getEtudiant().getNom()).isEqualTo("Ben Ali");
            assertThat(result.getLot().getNumeroLot()).isEqualTo(1);
            verify(repository, times(1)).save(any(ExamenParticipation.class));
        }
    }

    @Nested
    @DisplayName("delete()")
    class Delete {

        @Test
        @DisplayName("Doit appeler deleteById avec le bon ID")
        void delete_devraitAppelerDeleteById() {
            doNothing().when(repository).deleteById(1L);

            service.delete(1L);

            verify(repository, times(1)).deleteById(1L);
        }
    }
}
