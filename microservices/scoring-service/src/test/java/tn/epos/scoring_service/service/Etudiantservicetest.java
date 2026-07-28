package tn.epos.scoring_service.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tn.epos.scoring_service.dto.ImportEtudiantRequest;
import tn.epos.scoring_service.dto.ImportResult;
import tn.epos.scoring_service.entities.Etudiant;
import tn.epos.scoring_service.entities.ExamenParticipation;
import tn.epos.scoring_service.repositories.IEtudiantRepository;
import tn.epos.scoring_service.repositories.IExamenParticipationRepository;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("EtudiantService - Tests unitaires")
class EtudiantServiceTest {

    @Mock
    private IEtudiantRepository etudiantRepository;

    @Mock
    private IExamenParticipationRepository participationRepository;

    @InjectMocks
    private EtudiantService etudiantService;

    private Etudiant etudiant;

    @BeforeEach
    void setUp() {
        // Etudiant : id, numero_inscription, nom, prenom, participations
        etudiant = new Etudiant();
        etudiant.setId(1L);
        etudiant.setNumero_inscription("2024-001");
        etudiant.setNom("Ben Ali");
        etudiant.setPrenom("Mohamed");
    }

    @Nested
    @DisplayName("getAllEtudiants()")
    class GetAllEtudiants {

        @Test
        @DisplayName("Doit retourner la liste de tous les étudiants")
        void getAllEtudiants_devraitRetournerListe() {
            when(etudiantRepository.findAll()).thenReturn(List.of(etudiant));

            List<Etudiant> result = etudiantService.getAllEtudiants();

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getNom()).isEqualTo("Ben Ali");
            assertThat(result.get(0).getNumero_inscription()).isEqualTo("2024-001");
            verify(etudiantRepository, times(1)).findAll();
        }

        @Test
        @DisplayName("Doit retourner une liste vide si aucun étudiant")
        void getAllEtudiants_devraitRetournerListeVide() {
            when(etudiantRepository.findAll()).thenReturn(List.of());

            List<Etudiant> result = etudiantService.getAllEtudiants();

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("getEtudiantById()")
    class GetEtudiantById {

        @Test
        @DisplayName("Doit retourner l'étudiant si trouvé")
        void getEtudiantById_devraitRetournerEtudiant() {
            when(etudiantRepository.findById(1L)).thenReturn(Optional.of(etudiant));

            Optional<Etudiant> result = etudiantService.getEtudiantById(1L);

            assertThat(result).isPresent();
            assertThat(result.get().getId()).isEqualTo(1L);
            assertThat(result.get().getPrenom()).isEqualTo("Mohamed");
        }

        @Test
        @DisplayName("Doit retourner Optional vide si introuvable")
        void getEtudiantById_devraitRetournerVideSiIntrouvable() {
            when(etudiantRepository.findById(99L)).thenReturn(Optional.empty());

            Optional<Etudiant> result = etudiantService.getEtudiantById(99L);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("saveEtudiant()")
    class SaveEtudiant {

        @Test
        @DisplayName("Doit sauvegarder et retourner l'étudiant")
        void saveEtudiant_devraitSauvegarder() {
            when(etudiantRepository.save(any(Etudiant.class))).thenReturn(etudiant);

            Etudiant result = etudiantService.saveEtudiant(etudiant);

            assertThat(result).isNotNull();
            assertThat(result.getNom()).isEqualTo("Ben Ali");
            assertThat(result.getPrenom()).isEqualTo("Mohamed");
            verify(etudiantRepository, times(1)).save(any(Etudiant.class));
        }
    }

    @Nested
    @DisplayName("deleteEtudiant()")
    class DeleteEtudiant {

        @Test
        @DisplayName("Doit appeler deleteById avec le bon ID")
        void deleteEtudiant_devraitAppelerDeleteById() {
            doNothing().when(etudiantRepository).deleteById(1L);

            etudiantService.deleteEtudiant(1L);

            verify(etudiantRepository, times(1)).deleteById(1L);
        }
    }

    @Nested
    @DisplayName("importStudents() - import en masse (gap #11)")
    class ImportStudents {

        private static final Long EXAM = 10L;

        @Test
        @DisplayName("Nouvel étudiant : crée le répertoire ET inscrit -> CREATED")
        void importStudents_nouveau_devraitCreerEtInscrire() {
            when(etudiantRepository.findByNumeroInscription("N-1")).thenReturn(List.of());
            when(etudiantRepository.save(any(Etudiant.class))).thenAnswer(inv -> {
                Etudiant e = inv.getArgument(0);
                e.setId(42L);
                return e;
            });
            when(participationRepository.existsByExamenAndEtudiant(EXAM, 42L)).thenReturn(false);

            ImportResult result = etudiantService.importStudents(EXAM,
                    List.of(new ImportEtudiantRequest("Nom", "Prenom", "N-1",null)));

            assertThat(result.total()).isEqualTo(1);
            assertThat(result.created()).isEqualTo(1);
            assertThat(result.enrolled()).isZero();
            assertThat(result.alreadyEnrolled()).isZero();
            assertThat(result.errors()).isZero();
            assertThat(result.rows().get(0).statut()).isEqualTo("CREATED");
            verify(participationRepository, times(1)).save(any(ExamenParticipation.class));
        }

        @Test
        @DisplayName("Étudiant existant non inscrit : réutilise + inscrit -> ENROLLED")
        void importStudents_existantNonInscrit_devraitInscrire() {
            when(etudiantRepository.findByNumeroInscription("2024-001")).thenReturn(List.of(etudiant));
            when(participationRepository.existsByExamenAndEtudiant(EXAM, 1L)).thenReturn(false);

            ImportResult result = etudiantService.importStudents(EXAM,
                    List.of(new ImportEtudiantRequest("Ben Ali", "Mohamed", "2024-001", null)));

            assertThat(result.created()).isZero();
            assertThat(result.enrolled()).isEqualTo(1);
            assertThat(result.rows().get(0).statut()).isEqualTo("ENROLLED");
            // existing student reused, NOT re-created
            verify(etudiantRepository, never()).save(any(Etudiant.class));
            verify(participationRepository, times(1)).save(any(ExamenParticipation.class));
        }

        @Test
        @DisplayName("Étudiant déjà inscrit : ignore -> ALREADY_ENROLLED")
        void importStudents_dejaInscrit_devraitIgnorer() {
            when(etudiantRepository.findByNumeroInscription("2024-001")).thenReturn(List.of(etudiant));
            when(participationRepository.existsByExamenAndEtudiant(EXAM, 1L)).thenReturn(true);

            ImportResult result = etudiantService.importStudents(EXAM,
                    List.of(new ImportEtudiantRequest("Ben Ali", "Mohamed", "2024-001", null)));

            assertThat(result.alreadyEnrolled()).isEqualTo(1);
            assertThat(result.rows().get(0).statut()).isEqualTo("ALREADY_ENROLLED");
            verify(participationRepository, never()).save(any(ExamenParticipation.class));
        }

        @Test
        @DisplayName("Ligne sans numéro ou sans nom -> ERROR, sans toucher la base")
        void importStudents_champsManquants_devraitEchouer() {
            ImportResult result = etudiantService.importStudents(EXAM, List.of(
                    new ImportEtudiantRequest("", "Prenom", "N-2", null),   // nom manquant
                    new ImportEtudiantRequest("Nom", "Prenom", "  ", null)   // numéro manquant
            ));

            assertThat(result.errors()).isEqualTo(2);
            assertThat(result.created()).isZero();
            assertThat(result.enrolled()).isZero();
            assertThat(result.rows()).allMatch(r -> "ERROR".equals(r.statut()));
            verify(etudiantRepository, never()).save(any(Etudiant.class));
            verify(participationRepository, never()).save(any(ExamenParticipation.class));
        }

        @Test
        @DisplayName("Lot mixte : compteurs cohérents (somme == total)")
        void importStudents_mixte_compteursCoherents() {
            // row1 new+enrol, row2 existing+enrol, row3 already, row4 error
            when(etudiantRepository.findByNumeroInscription("NEW")).thenReturn(List.of());
            when(etudiantRepository.save(any(Etudiant.class))).thenAnswer(inv -> {
                Etudiant e = inv.getArgument(0);
                e.setId(99L);
                return e;
            });
            when(etudiantRepository.findByNumeroInscription("2024-001")).thenReturn(List.of(etudiant));
            when(participationRepository.existsByExamenAndEtudiant(eq(EXAM), anyLong()))
                    .thenReturn(false)   // row1 (id 99)
                    .thenReturn(false)   // row2 (id 1)
                    .thenReturn(true);   // row3 (id 1) already enrolled

            ImportResult result = etudiantService.importStudents(EXAM, List.of(
                    new ImportEtudiantRequest("New", "Student", "NEW", null),
                    new ImportEtudiantRequest("Ben Ali", "Mohamed", "2024-001" , null),
                    new ImportEtudiantRequest("Ben Ali", "Mohamed", "2024-001" , null),
                    new ImportEtudiantRequest("", "", "", null)
            ));

            assertThat(result.total()).isEqualTo(4);
            assertThat(result.created()).isEqualTo(1);
            assertThat(result.enrolled()).isEqualTo(1);
            assertThat(result.alreadyEnrolled()).isEqualTo(1);
            assertThat(result.errors()).isEqualTo(1);
            assertThat(result.created() + result.enrolled()
                    + result.alreadyEnrolled() + result.errors()).isEqualTo(result.total());
        }
    }

    /**
     * #227 — un réimport fait « juste pour ajouter les e-mails » range TOUTES les
     * lignes en ALREADY_ENROLLED. Sans compteur dédié, le bilan affiche
     * « 0 créé, 0 inscrit » : indiscernable d'un import sans effet, alors que
     * c'est exactement ce que l'enseignant venait faire.
     */
    @Nested
    @DisplayName("importStudents() - retour honnête sur les e-mails (#227)")
    class RetourEmails {

        private static final Long EXAM = 10L;

        private Etudiant etu(long id, String numero, String email) {
            Etudiant e = new Etudiant();
            e.setId(id);
            e.setNumero_inscription(numero);
            e.setNom("N" + id);
            e.setEmail(email);
            return e;
        }

        @Test
        @DisplayName("Réimport qui renseigne une adresse manquante : compté ET dit dans la ligne")
        void reimportAvecEmail_devraitEtreCompteEtAnnonce() {
            Etudiant sansEmail = etu(1L, "A-1", "");
            when(etudiantRepository.findByNumeroInscription("A-1")).thenReturn(List.of(sansEmail));
            when(participationRepository.existsByExamenAndEtudiant(EXAM, 1L)).thenReturn(true);
            when(participationRepository.findByExamenId(EXAM)).thenReturn(List.of());

            ImportResult result = etudiantService.importStudents(EXAM, List.of(
                    new ImportEtudiantRequest("N1", "P1", "A-1", "sarra.amri@etu.tn")));

            assertThat(sansEmail.getEmail()).isEqualTo("sarra.amri@etu.tn");
            assertThat(result.emailsRenseignes()).isEqualTo(1);
            // Le bilan « statut » reste un no-op — d'où l'utilité du compteur.
            assertThat(result.created()).isZero();
            assertThat(result.alreadyEnrolled()).isEqualTo(1);
            assertThat(result.rows().get(0).message()).contains("adresse e-mail mise à jour");
        }

        @Test
        @DisplayName("Adresse inchangée : rien n'est compté, le message reste neutre")
        void memeAdresse_neDoitRienCompter() {
            Etudiant avecEmail = etu(1L, "A-1", "deja@etu.tn");
            when(etudiantRepository.findByNumeroInscription("A-1")).thenReturn(List.of(avecEmail));
            when(participationRepository.existsByExamenAndEtudiant(EXAM, 1L)).thenReturn(true);
            when(participationRepository.findByExamenId(EXAM)).thenReturn(List.of());

            ImportResult result = etudiantService.importStudents(EXAM, List.of(
                    new ImportEtudiantRequest("N1", "P1", "A-1", "deja@etu.tn")));

            assertThat(result.emailsRenseignes()).isZero();
            assertThat(result.rows().get(0).message()).isEqualTo("Déjà inscrit à cet examen.");
        }

        @Test
        @DisplayName("Colonne e-mail vide : n'EFFACE jamais une adresse déjà connue")
        void colonneVide_neDoitPasEffacer() {
            Etudiant avecEmail = etu(1L, "A-1", "connu@etu.tn");
            when(etudiantRepository.findByNumeroInscription("A-1")).thenReturn(List.of(avecEmail));
            when(participationRepository.existsByExamenAndEtudiant(EXAM, 1L)).thenReturn(true);
            when(participationRepository.findByExamenId(EXAM)).thenReturn(List.of());

            // Un fichier sans colonne email ne veut pas dire « supprime les adresses ».
            ImportResult result = etudiantService.importStudents(EXAM, List.of(
                    new ImportEtudiantRequest("N1", "P1", "A-1", "")));

            assertThat(avecEmail.getEmail()).isEqualTo("connu@etu.tn");
            assertThat(result.emailsRenseignes()).isZero();
        }
    }

    /**
     * #256/#227 — le dernier fichier importé fait foi, et deux imports successifs
     * ne doivent JAMAIS produire deux fois la même position. Le compteur de ligne
     * étant local à l'appel, l'ancien code repartait à 1 à chaque import : sur le
     * terrain, l'étudiant de la ligne 1 du 2e fichier passait devant celui de la
     * ligne 1 du 1er, et la composition des lots devenait arbitraire.
     */
    @Nested
    @DisplayName("importStudents() - ordre du listing (#256)")
    class OrdreListing {

        private static final Long EXAM = 10L;

        /** Un étudiant du répertoire, déjà persisté. */
        private Etudiant etu(long id, String numero) {
            Etudiant e = new Etudiant();
            e.setId(id);
            e.setNumero_inscription(numero);
            e.setNom("N" + id);
            return e;
        }

        /** Une participation existante, à la position donnée. */
        private ExamenParticipation part(long id, Etudiant e, Integer ordre) {
            ExamenParticipation p = new ExamenParticipation();
            p.setId(id);
            p.setExamen_id(EXAM);
            p.setEtudiant(e);
            p.setOrdre_import(ordre);
            return p;
        }

        @SuppressWarnings("unchecked")
        private List<ExamenParticipation> captureSaveAll() {
            org.mockito.ArgumentCaptor<List<ExamenParticipation>> cap =
                    org.mockito.ArgumentCaptor.forClass(List.class);
            verify(participationRepository).saveAll(cap.capture());
            return cap.getValue();
        }

        @Test
        @DisplayName("Retardataires : ajoutés à la SUITE, jamais devant — et aucun doublon")
        void deuxiemeImport_neDoitPasDupliquerLesPositions() {
            Etudiant a = etu(1L, "A-1"); // déjà inscrit, position 1
            Etudiant b = etu(2L, "B-2"); // déjà inscrit, position 2
            Etudiant c = etu(3L, "C-3"); // le nouveau du 2e fichier

            ExamenParticipation pa = part(100L, a, 1);
            ExamenParticipation pb = part(101L, b, 2);
            ExamenParticipation pc = part(102L, c, null);

            when(etudiantRepository.findByNumeroInscription("C-3")).thenReturn(List.of(c));
            when(participationRepository.existsByExamenAndEtudiant(EXAM, 3L)).thenReturn(false);
            when(participationRepository.findByExamenId(EXAM)).thenReturn(List.of(pa, pb, pc));

            // 2e import : un seul étudiant, un retardataire.
            etudiantService.importStudents(EXAM, List.of(
                    new ImportEtudiantRequest("N3", "P3", "C-3", null)));

            captureSaveAll();

            // Le fichier ne couvre PAS tout le listing → c'est un ajout : les
            // inscrits gardent leur place, le retardataire se range à la fin.
            assertThat(pa.getOrdre_import()).isEqualTo(1);
            assertThat(pb.getOrdre_import()).isEqualTo(2);
            assertThat(pc.getOrdre_import()).isEqualTo(3);

            // L'invariant qui manquait : les positions sont uniques.
            assertThat(List.of(pa, pb, pc).stream().map(ExamenParticipation::getOrdre_import))
                    .doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("Réimport du fichier corrigé : l'ordre du NOUVEAU fichier fait foi")
        void reimportFichierCorrige_devraitReordonner() {
            Etudiant a = etu(1L, "A-1");
            Etudiant b = etu(2L, "B-2");
            ExamenParticipation pa = part(100L, a, 1);
            ExamenParticipation pb = part(101L, b, 2);

            when(etudiantRepository.findByNumeroInscription("B-2")).thenReturn(List.of(b));
            when(etudiantRepository.findByNumeroInscription("A-1")).thenReturn(List.of(a));
            when(participationRepository.existsByExamenAndEtudiant(eq(EXAM), anyLong())).thenReturn(true);
            when(participationRepository.findByExamenId(EXAM)).thenReturn(List.of(pa, pb));

            // Même effectif, ordre inversé dans le fichier.
            etudiantService.importStudents(EXAM, List.of(
                    new ImportEtudiantRequest("N2", "P2", "B-2", null),
                    new ImportEtudiantRequest("N1", "P1", "A-1", null)));

            captureSaveAll();

            assertThat(pb.getOrdre_import()).isEqualTo(1);
            assertThat(pa.getOrdre_import()).isEqualTo(2);
        }

        @Test
        @DisplayName("Un ajout manuel garde ordre_import null : il passe APRÈS le fichier")
        void ajoutManuel_devraitResterNull() {
            Etudiant a = etu(1L, "A-1");
            Etudiant manuel = etu(9L, "M-9");
            ExamenParticipation pa = part(100L, a, 1);
            ExamenParticipation pm = part(109L, manuel, null); // ajouté à la main

            when(etudiantRepository.findByNumeroInscription("A-1")).thenReturn(List.of(a));
            when(participationRepository.existsByExamenAndEtudiant(EXAM, 1L)).thenReturn(true);
            when(participationRepository.findByExamenId(EXAM)).thenReturn(List.of(pa, pm));

            etudiantService.importStudents(EXAM, List.of(
                    new ImportEtudiantRequest("N1", "P1", "A-1", null)));

            captureSaveAll();

            assertThat(pa.getOrdre_import()).isEqualTo(1);
            // Jamais renuméroté : LotAssignmentService s'appuie sur ce null.
            assertThat(pm.getOrdre_import()).isNull();
        }

        @Test
        @DisplayName("Un doublon dans le fichier ne décale pas les positions suivantes")
        void doublonDansLeFichier_neDoitPasDecaler() {
            Etudiant a = etu(1L, "A-1");
            Etudiant b = etu(2L, "B-2");
            ExamenParticipation pa = part(100L, a, 1);
            ExamenParticipation pb = part(101L, b, 2);

            when(etudiantRepository.findByNumeroInscription("A-1")).thenReturn(List.of(a));
            when(etudiantRepository.findByNumeroInscription("B-2")).thenReturn(List.of(b));
            when(participationRepository.existsByExamenAndEtudiant(eq(EXAM), anyLong())).thenReturn(true);
            when(participationRepository.findByExamenId(EXAM)).thenReturn(List.of(pa, pb));

            // "A-1" apparaît deux fois dans le fichier.
            etudiantService.importStudents(EXAM, List.of(
                    new ImportEtudiantRequest("N1", "P1", "A-1", null),
                    new ImportEtudiantRequest("N1", "P1", "A-1", null),
                    new ImportEtudiantRequest("N2", "P2", "B-2", null)));

            captureSaveAll();

            assertThat(pa.getOrdre_import()).isEqualTo(1);
            // 2 et non 3 : la 2e occurrence est ignorée, pas comptée comme une ligne.
            assertThat(pb.getOrdre_import()).isEqualTo(2);
        }
    }
}
