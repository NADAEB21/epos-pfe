package tn.epos.auth_service.audit;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogRepository auditLogRepository;

    @Async
    public void log(Long userId, String email, AuditAction action, String details, String ipAddress) {
        AuditLog entry = AuditLog.builder()
                .userId(userId)
                .email(email)
                .action(action)
                .details(details)
                .ipAddress(ipAddress)
                .build();
        auditLogRepository.save(entry);
    }

    /**
     * #289 — variante ATTRIBUEE : {@code acteurId} est la personne qui agit,
     * {@code userId} celle qui subit. A utiliser pour tout acte administratif.
     */
    @Async
    public void logAttribue(Long userId, String email, AuditAction action,
                            String details, String ipAddress, Long acteurId) {
        auditLogRepository.save(AuditLog.builder()
                .userId(userId)
                .email(email)
                .action(action)
                .details(details)
                .ipAddress(ipAddress)
                .acteurId(acteurId)
                .build());
    }

    @Async
    public void log(Long userId, String email, AuditAction action) {
        log(userId, email, action, null, null);
    }
}
