package tn.epos.auth_service.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tn.epos.common.dto.ApiResponse;
import tn.epos.auth_service.dto.RoleAssignmentDto;
import tn.epos.auth_service.dto.UserCreateRequest;
import tn.epos.auth_service.dto.UserResponse;
import tn.epos.auth_service.entity.RoleType;
import tn.epos.auth_service.service.UserService;

import java.util.List;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /**
     * GET /api/v1/users[?role=EVALUATEUR]
     * SUPER_ADMIN or RESPONSABLE_MATIERE may call. Optional ?role param filters
     * the response server-side (responsable typically asks for ?role=EVALUATEUR
     * to populate the station-assignment picker — see #80).
     */
    @GetMapping
    @PreAuthorize("""
            hasAuthority('ROLE_SUPER_ADMIN') or \
            !authentication.authorities.?[authority.startsWith('ROLE_RESPONSABLE_MATIERE')].empty\
            """)
    public ResponseEntity<ApiResponse<List<UserResponse>>> getAllUsers(
            @RequestParam(required = false) RoleType role) {
        return ResponseEntity.ok(ApiResponse.ok(userService.getAllUsers(role)));
    }

    /**
     * POST /api/v1/users
     * SUPER_ADMIN can create any user.
     * RESPONSABLE_MATIERE can create users scoped to their matiere_id — the
     * delegation constraint is enforced in UserService, not here.
     *
     * SpEL collection filter: checks whether the caller has at least one
     * authority string that starts with "ROLE_RESPONSABLE_MATIERE".
     */
    @PostMapping
    @PreAuthorize("""
            hasAuthority('ROLE_SUPER_ADMIN') or \
            !authentication.authorities.?[authority.startsWith('ROLE_RESPONSABLE_MATIERE')].empty\
            """)
    public ResponseEntity<ApiResponse<UserResponse>> createUser(
            @Valid @RequestBody UserCreateRequest request,
            Authentication authentication) {
        UserResponse created = userService.createUser(request, authentication);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(created));
    }

    /**
     * POST /api/v1/users/{id}/roles
     * ADDS roles to a user, leaving their existing roles untouched. Idempotent —
     * re-adding a role the user already holds is a no-op, not a duplicate row.
     *
     * This is the endpoint to use for the common case: a RESPONSABLE_MATIERE who
     * must also evaluate ("cet examen, il l'évalue lui-même"). Creating a second
     * account with the same email is impossible by design — one email is one
     * person, and a person carries several roles.
     */
    @PostMapping("/{id}/roles")
    @PreAuthorize("""
            hasAuthority('ROLE_SUPER_ADMIN') or \
            !authentication.authorities.?[authority.startsWith('ROLE_RESPONSABLE_MATIERE')].empty\
            """)
    public ResponseEntity<ApiResponse<Void>> addRoles(
            @PathVariable Long id,
            @Valid @RequestBody List<RoleAssignmentDto> roles,
            Authentication authentication) {
        userService.addRoles(id, roles, authentication);
        return ResponseEntity.ok(ApiResponse.ok("Roles added"));
    }

    /**
     * PUT /api/v1/users/{id}/roles
     * REPLACES the user's entire role list with the payload — any role not sent
     * is revoked. Use POST /{id}/roles to add a role without that risk.
     *
     * Any SUPER_ADMIN or RESPONSABLE_MATIERE may attempt; UserService enforces
     * the per-role delegation rules (who can assign what to whom).
     */
    @PutMapping("/{id}/roles")
    @PreAuthorize("""
            hasAuthority('ROLE_SUPER_ADMIN') or \
            !authentication.authorities.?[authority.startsWith('ROLE_RESPONSABLE_MATIERE')].empty\
            """)
    public ResponseEntity<ApiResponse<Void>> assignRoles(
            @PathVariable Long id,
            @Valid @RequestBody List<RoleAssignmentDto> roles,
            Authentication authentication) {
        userService.assignRoles(id, roles, authentication);
        return ResponseEntity.ok(ApiResponse.ok("Roles updated"));
    }

    /**
     * DELETE /api/v1/users/{id}
     * SUPER_ADMIN only.
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<Void>> deleteUser(@PathVariable Long id) {
        userService.deactivateUser(id);
        return ResponseEntity.ok(ApiResponse.ok("User deactivated"));
    }
}
