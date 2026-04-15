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
    private LocalDateTime createdAt;
    private List<RoleAssignmentDto> roles;
}
