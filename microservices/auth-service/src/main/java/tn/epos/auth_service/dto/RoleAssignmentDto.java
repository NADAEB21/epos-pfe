package tn.epos.auth_service.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import tn.epos.auth_service.entity.RoleType;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoleAssignmentDto {

    @NotNull
    private RoleType role;

    /** Required when role == RESPONSABLE_MATIERE, must be null otherwise. */
    private Long matiereId;
}
