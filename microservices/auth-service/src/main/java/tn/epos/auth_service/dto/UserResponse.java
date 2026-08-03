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
     * #294 — fin du verrou temporaire, ou {@code null} si le compte n'est pas
     * verrouillé. Distinct de {@code isActive} : un écran peut enfin dire
     * « verrouillé jusqu'à 09:12 » plutôt que « inactif, allez savoir pourquoi ».
     */
    private java.time.LocalDateTime lockedUntil;
    private LocalDateTime createdAt;
    private List<RoleAssignmentDto> roles;
}
