package tn.epos.auth_service.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import tn.epos.auth_service.audit.AuditAction;
import tn.epos.auth_service.audit.AuditService;
import tn.epos.auth_service.dto.MatiereImportResult;
import tn.epos.auth_service.dto.MatiereImportRow;
import tn.epos.auth_service.dto.MatiereRequest;
import tn.epos.auth_service.entity.Matiere;
import tn.epos.auth_service.exception.MatiereConflictException;
import tn.epos.auth_service.exception.MatiereNotFoundException;
import tn.epos.auth_service.repository.MatiereRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * #134 — le catalogue des matières. Ce que ces tests épinglent :
 * l'unicité du code se compare SANS la casse (leçon #285), le retrait est
 * motivé/attribué/réversible (doctrine #289), il n'existe aucun DELETE, et
 * l'import en lot rend un verdict par ligne sans qu'une ligne invalide
 * n'annule les lignes valides.
 */
@ExtendWith(MockitoExtension.class)
class MatiereServiceTest {

    @Mock private MatiereRepository matiereRepository;
    @Mock private AuditService auditService;

    /** La date du retrait est une donnée, pas un hasard : horloge fixe. */
    @Spy private Clock clock =
            Clock.fixed(Instant.parse("2026-08-06T09:00:00Z"), ZoneId.of("UTC"));

    @InjectMocks private MatiereService matiereService;

    private Matiere chimie(boolean active) {
        return Matiere.builder()
                .id(1L).code("CHIM_THER").libelle("Chimie thérapeutique")
                .active(active)
                .build();
    }

    // =========================================================================
    // Création — l'unicité du code ignore la casse (#285)
    // =========================================================================

    @Test
    void creer_codeLibre_enregistreEtAuditeAttribue() {
        when(matiereRepository.findByCodeIgnoreCase("BIOCHIM")).thenReturn(Optional.empty());
        when(matiereRepository.save(any(Matiere.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var created = matiereService.creer(
                new MatiereRequest("  BIOCHIM ", " Biochimie clinique "), 42L, "admin@epos.tn");

        // Les espaces collés par un copier-coller ne font pas partie du code.
        assertThat(created.code()).isEqualTo("BIOCHIM");
        assertThat(created.libelle()).isEqualTo("Biochimie clinique");
        assertThat(created.active()).isTrue();
        verify(auditService).logAttribue(isNull(), eq("admin@epos.tn"),
                eq(AuditAction.MATIERE_CREATED), anyString(), isNull(), eq(42L));
    }

    @Test
    void creer_codeDejaPris_memeEnMinuscules_refuse409() {
        // « chim_ther » et « CHIM_THER » seraient la même matière en double —
        // exactement le piège de l'unicité d'e-mail (#285).
        when(matiereRepository.findByCodeIgnoreCase("chim_ther"))
                .thenReturn(Optional.of(chimie(true)));

        assertThatThrownBy(() -> matiereService.creer(
                new MatiereRequest("chim_ther", "Doublon"), 42L, "admin@epos.tn"))
                .isInstanceOf(MatiereConflictException.class)
                .hasMessageContaining("Chimie thérapeutique");

        verify(matiereRepository, never()).save(any());
    }

    @Test
    void creer_codeDuneMatiereRetiree_orienteVersLaReouverture() {
        // Recréer une matière retirée sous le même code fabriquerait un
        // doublon d'identité : le message dit le bon geste (rouvrir).
        when(matiereRepository.findByCodeIgnoreCase("CHIM_THER"))
                .thenReturn(Optional.of(chimie(false)));

        assertThatThrownBy(() -> matiereService.creer(
                new MatiereRequest("CHIM_THER", "Chimie thérapeutique"), 42L, "admin@epos.tn"))
                .isInstanceOf(MatiereConflictException.class)
                .hasMessageContaining("rouvrez-la");
    }

    // =========================================================================
    // Renommage — les références (par id) restent intactes
    // =========================================================================

    @Test
    void modifier_renommage_conserveLIdEtAuditeAvantApres() {
        Matiere existante = chimie(true);
        when(matiereRepository.findById(1L)).thenReturn(Optional.of(existante));
        when(matiereRepository.findByCodeIgnoreCase("CHIM_THER"))
                .thenReturn(Optional.of(existante));
        when(matiereRepository.save(any(Matiere.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var updated = matiereService.modifier(1L,
                new MatiereRequest("CHIM_THER", "Chimie thérapeutique II"), 42L, "admin@epos.tn");

        assertThat(updated.id()).isEqualTo(1L);
        assertThat(updated.libelle()).isEqualTo("Chimie thérapeutique II");
        // L'audit raconte le changement, pas seulement son résultat.
        ArgumentCaptor<String> details = ArgumentCaptor.forClass(String.class);
        verify(auditService).logAttribue(isNull(), eq("admin@epos.tn"),
                eq(AuditAction.MATIERE_UPDATED), details.capture(), isNull(), eq(42L));
        assertThat(details.getValue()).contains("Chimie thérapeutique")
                .contains("Chimie thérapeutique II");
    }

    @Test
    void modifier_versUnCodeDejaPrisParUneAutre_refuse409() {
        when(matiereRepository.findById(2L)).thenReturn(Optional.of(
                Matiere.builder().id(2L).code("PHARMACO").libelle("Pharmacologie").build()));
        when(matiereRepository.findByCodeIgnoreCase("CHIM_THER"))
                .thenReturn(Optional.of(chimie(true)));

        assertThatThrownBy(() -> matiereService.modifier(2L,
                new MatiereRequest("CHIM_THER", "Pharmacologie"), 42L, "admin@epos.tn"))
                .isInstanceOf(MatiereConflictException.class);

        verify(matiereRepository, never()).save(any());
    }

    @Test
    void modifier_matiereInconnue_404() {
        when(matiereRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> matiereService.modifier(99L,
                new MatiereRequest("X", "Y"), 42L, "admin@epos.tn"))
                .isInstanceOf(MatiereNotFoundException.class);
    }

    // =========================================================================
    // Retrait / réouverture — motivé, attribué, réversible (#289)
    // =========================================================================

    @Test
    void retirer_poseLaProvenanceCompleteEtAudite() {
        when(matiereRepository.findById(1L)).thenReturn(Optional.of(chimie(true)));
        when(matiereRepository.save(any(Matiere.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var retired = matiereService.retirer(1L, "Fermée à la rentrée 2026", 42L, "admin@epos.tn");

        assertThat(retired.active()).isFalse();
        assertThat(retired.retirementMotif()).isEqualTo("Fermée à la rentrée 2026");
        assertThat(retired.retiredBy()).isEqualTo(42L);
        // Horloge fixe : la date vient du Clock injecté, pas du poste.
        assertThat(retired.retiredAt())
                .isEqualTo(LocalDateTime.of(2026, 8, 6, 9, 0));
        verify(auditService).logAttribue(isNull(), eq("admin@epos.tn"),
                eq(AuditAction.MATIERE_RETIRED), anyString(), isNull(), eq(42L));
    }

    @Test
    void retirer_dejaRetiree_refuse409() {
        when(matiereRepository.findById(1L)).thenReturn(Optional.of(chimie(false)));

        assertThatThrownBy(() -> matiereService.retirer(1L, "encore", 42L, "admin@epos.tn"))
                .isInstanceOf(MatiereConflictException.class)
                .hasMessageContaining("déjà retirée");

        verify(matiereRepository, never()).save(any());
    }

    @Test
    void reactiver_effaceLaProvenanceDuRetrait() {
        Matiere retiree = chimie(false);
        retiree.setRetiredAt(LocalDateTime.of(2026, 1, 1, 8, 0));
        retiree.setRetiredBy(7L);
        retiree.setRetirementMotif("ancienne fermeture");
        when(matiereRepository.findById(1L)).thenReturn(Optional.of(retiree));
        when(matiereRepository.save(any(Matiere.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var reopened = matiereService.reactiver(1L, "La matière reprend", 42L, "admin@epos.tn");

        assertThat(reopened.active()).isTrue();
        assertThat(reopened.retiredAt()).isNull();
        assertThat(reopened.retiredBy()).isNull();
        assertThat(reopened.retirementMotif()).isNull();
        verify(auditService).logAttribue(isNull(), eq("admin@epos.tn"),
                eq(AuditAction.MATIERE_REACTIVATED), anyString(), isNull(), eq(42L));
    }

    @Test
    void reactiver_dejaActive_refuse409() {
        when(matiereRepository.findById(1L)).thenReturn(Optional.of(chimie(true)));

        assertThatThrownBy(() -> matiereService.reactiver(1L, "?", 42L, "admin@epos.tn"))
                .isInstanceOf(MatiereConflictException.class)
                .hasMessageContaining("déjà active");
    }

    // =========================================================================
    // Import en lot — verdict par ligne, meilleur effort
    // =========================================================================

    @Test
    void importer_melangeValideInvalideDoublon_verdictParLigneEtLesValidesPassent() {
        when(matiereRepository.findByCodeIgnoreCase("BIOCHIM")).thenReturn(Optional.empty());
        when(matiereRepository.findByCodeIgnoreCase("MICRO")).thenReturn(Optional.empty());
        when(matiereRepository.findByCodeIgnoreCase("CHIM_THER"))
                .thenReturn(Optional.of(chimie(true)));
        when(matiereRepository.save(any(Matiere.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        MatiereImportResult result = matiereService.importer(List.of(
                new MatiereImportRow("BIOCHIM", "Biochimie clinique"),   // valide
                new MatiereImportRow("CHIM_THER", "Chimie"),             // déjà au catalogue
                new MatiereImportRow("", "Sans code"),                   // invalide
                new MatiereImportRow("MICRO", "Microbiologie"),          // valide — doit passer malgré la ligne 3
                new MatiereImportRow("micro", "Doublon interne")         // doublon DANS l'envoi, casse différente
        ), 42L, "admin@epos.tn");

        assertThat(result.crees()).isEqualTo(2);
        assertThat(result.doublons()).isEqualTo(2);
        assertThat(result.erreurs()).isEqualTo(1);
        assertThat(result.rows()).hasSize(5);
        assertThat(result.rows().get(0).statut()).isEqualTo(MatiereImportResult.Statut.CREATED);
        assertThat(result.rows().get(1).statut()).isEqualTo(MatiereImportResult.Statut.DUPLICATE);
        assertThat(result.rows().get(2).statut()).isEqualTo(MatiereImportResult.Statut.ERROR);
        // La ligne 1-basée pointe la ligne du tableau collé, pas un index interne.
        assertThat(result.rows().get(2).ligne()).isEqualTo(3);
        assertThat(result.rows().get(3).statut()).isEqualTo(MatiereImportResult.Statut.CREATED);
        assertThat(result.rows().get(4).statut()).isEqualTo(MatiereImportResult.Statut.DUPLICATE);

        verify(auditService).logAttribue(isNull(), eq("admin@epos.tn"),
                eq(AuditAction.MATIERE_IMPORTED), anyString(), isNull(), eq(42L));
    }

    @Test
    void importer_courseSurLIndexUnique_devientUnDoublonPasUn500() {
        // Créée entre le contrôle et l'insert (autre onglet, autre admin) :
        // la ligne finit DOUBLON et les suivantes continuent.
        when(matiereRepository.findByCodeIgnoreCase(anyString())).thenReturn(Optional.empty());
        when(matiereRepository.save(any(Matiere.class)))
                .thenThrow(new DataIntegrityViolationException("ux_matieres_code"))
                .thenAnswer(inv -> inv.getArgument(0));

        MatiereImportResult result = matiereService.importer(List.of(
                new MatiereImportRow("BIOCHIM", "Biochimie"),
                new MatiereImportRow("MICRO", "Microbiologie")
        ), 42L, "admin@epos.tn");

        assertThat(result.rows().get(0).statut()).isEqualTo(MatiereImportResult.Statut.DUPLICATE);
        assertThat(result.rows().get(1).statut()).isEqualTo(MatiereImportResult.Statut.CREATED);
        assertThat(result.crees()).isEqualTo(1);
        assertThat(result.doublons()).isEqualTo(1);
    }
}
