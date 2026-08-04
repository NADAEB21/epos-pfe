package tn.epos.auth_service.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Builder
public class UserResponse {

    private Long id;
    private String email;
    private String nom;
    private String prenom;
    private Boolean isActive;

    /**
     * #294 — fin du verrou temporaire, ou {@code null}. Distinct de
     * {@code isActive} : un écran peut enfin dire « verrouillé jusqu'à 09:12 »
     * plutôt que « inactif, allez savoir pourquoi ».
     *
     * <p>⚠️ Porté AVEC son décalage horaire, pas en {@code LocalDateTime} : le
     * navigateur interpréterait un horodatage sans zone comme sa propre heure
     * locale, et un verrou encore actif passerait pour expiré dès que le serveur
     * et le poste ne partagent pas la même zone (mesuré : conteneur UTC, poste
     * UTC+2 — la ligne d'alerte ne s'affichait pas). Même piège qu'ADR-0010.
     */
    private java.time.OffsetDateTime lockedUntil;
    private LocalDateTime createdAt;
    private List<RoleAssignmentDto> roles;
}
