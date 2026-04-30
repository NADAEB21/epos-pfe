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

    @Async
    public void log(Long userId, String email, AuditAction action) {
        log(userId, email, action, null, null);
    }
}
