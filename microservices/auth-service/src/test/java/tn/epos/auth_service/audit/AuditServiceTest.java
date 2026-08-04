package tn.epos.auth_service.audit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

/**
 * #289 — le journal d'audit doit pouvoir répondre à « QUI a fait ça ? ».
 *
 * <p>Jusqu'ici il ne stockait que la CIBLE de l'action : pour la seule famille
 * d'actes où la question compte vraiment (retrait d'accès, attribution de
 * rôle), la réponse était absente. Ces tests épinglent la distinction entre
 * celui qui subit et celui qui décide.
 */
@ExtendWith(MockitoExtension.class)
class AuditServiceTest {

    @Mock private AuditLogRepository auditLogRepository;
    @InjectMocks private AuditService auditService;

    @Test
    void logAttribue_enregistreLaCibleETLAuteur() {
        auditService.logAttribue(7L, "cible@epos.tn", AuditAction.USER_DEACTIVATED,
                "Depart de la faculte", "10.0.0.1", 1L);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        AuditLog saved = captor.getValue();

        assertThat(saved.getUserId()).isEqualTo(7L);        // la personne qui subit
        assertThat(saved.getActeurId()).isEqualTo(1L);      // celle qui décide
        assertThat(saved.getAction()).isEqualTo(AuditAction.USER_DEACTIVATED);
        assertThat(saved.getDetails()).isEqualTo("Depart de la faculte");
        assertThat(saved.getIpAddress()).isEqualTo("10.0.0.1");
    }

    @Test
    void log_actesSansTiers_nEnregistreAucunActeur() {
        // Une connexion n'a pas d'auteur distinct : la cible EST l'auteur.
        auditService.log(7L, "cible@epos.tn", AuditAction.LOGIN_SUCCESS);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        assertThat(captor.getValue().getActeurId()).isNull();
    }
}
