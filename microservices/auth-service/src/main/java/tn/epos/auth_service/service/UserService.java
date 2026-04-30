package tn.epos.auth_service.service;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.epos.auth_service.audit.AuditAction;
import tn.epos.auth_service.audit.AuditService;
import tn.epos.auth_service.dto.RoleAssignmentDto;
import tn.epos.auth_service.dto.UserCreateRequest;
import tn.epos.auth_service.dto.UserResponse;
import tn.epos.auth_service.entity.RoleType;
import tn.epos.auth_service.entity.User;
import tn.epos.auth_service.entity.UserRole;
import tn.epos.auth_service.exception.EmailAlreadyExistsException;
import tn.epos.auth_service.exception.UnauthorizedDelegationException;
import tn.epos.auth_service.exception.UserNotFoundException;
import tn.epos.auth_service.repository.RefreshTokenRepository;
import tn.epos.auth_service.repository.UserRepository;
import tn.epos.auth_service.repository.UserRoleRepository;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserService {

    private static final String RESPONSABLE_PREFIX = "ROLE_RESPONSABLE_MATIERE:";

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    // -------------------------------------------------------------------------
    // Read
    // -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<UserResponse> getAllUsers() {
        return userRepository.findAll()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    // -------------------------------------------------------------------------
    // Create
    // -------------------------------------------------------------------------

    @Transactional
    public UserResponse createUser(UserCreateRequest request, Authentication authentication) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new EmailAlreadyExistsException(
                    "Email already in use: " + request.getEmail());
        }

        validateDelegation(request.getRoles(), authentication);

        User user = User.builder()
                .email(request.getEmail())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .nom(request.getNom())
                .prenom(request.getPrenom())
                .isActive(true)
                .failedLoginAttempts(0)
                .build();
        user = userRepository.save(user);

        List<UserRole> roles = buildUserRoles(user, request.getRoles());
        userRoleRepository.saveAll(roles);

        auditService.log(user.getId(), user.getEmail(), AuditAction.USER_CREATED,
                describeRoles(roles), null);

        return toResponse(user, roles);
    }

    // -------------------------------------------------------------------------
    // Role assignment
    // -------------------------------------------------------------------------

    @Transactional
    public void assignRoles(Long userId, List<RoleAssignmentDto> newRoleDtos,
                            Authentication authentication) {
        User user = findUserOrThrow(userId);

        validateDelegation(newRoleDtos, authentication);

        // Capture old roles for audit before deleting them
        List<UserRole> oldRoles = userRoleRepository.findByUserId(userId);
        String oldDesc = describeRoles(oldRoles);

        userRoleRepository.deleteByUserId(userId);

        List<UserRole> newRoles = buildUserRoles(user, newRoleDtos);
        userRoleRepository.saveAll(newRoles);

        auditService.log(user.getId(), user.getEmail(), AuditAction.ROLE_REVOKED,
                "Removed: " + oldDesc, null);
        auditService.log(user.getId(), user.getEmail(), AuditAction.ROLE_ASSIGNED,
                "Assigned: " + describeRoles(newRoles), null);
    }

    // -------------------------------------------------------------------------
    // Deactivate (soft delete)
    // -------------------------------------------------------------------------

    @Transactional
    public void deactivateUser(Long userId) {
        User user = findUserOrThrow(userId);

        user.setIsActive(false);
        userRepository.save(user);

        // Force all active sessions to expire immediately
        refreshTokenRepository.revokeAllByUserId(userId);

        auditService.log(user.getId(), user.getEmail(), AuditAction.USER_DEACTIVATED, null, null);
    }

    // -------------------------------------------------------------------------
    // Delegation constraint
    // -------------------------------------------------------------------------

    /**
     * Enforces RBAC delegation rules:
     *
     * - SUPER_ADMIN          → may assign any role with any (or null) matiereId
     * - RESPONSABLE_MATIERE  → may assign RESPONSABLE_MATIERE or EVALUATEUR,
     *                          but only within their own matiereId scope(s)
     * - EVALUATEUR           → no delegation rights whatsoever
     */
    private void validateDelegation(List<RoleAssignmentDto> rolesBeingAssigned,
                                    Authentication authentication) {
        boolean isSuperAdmin = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch("ROLE_SUPER_ADMIN"::equals);

        if (isSuperAdmin) {
            return; // unrestricted
        }

        Set<Long> actingScopes = getActingUserScope(authentication);

        if (actingScopes.isEmpty()) {
            throw new UnauthorizedDelegationException(
                    "EVALUATEUR has no delegation rights");
        }

        for (RoleAssignmentDto dto : rolesBeingAssigned) {
            switch (dto.getRole()) {
                case SUPER_ADMIN -> throw new UnauthorizedDelegationException(
                        "RESPONSABLE_MATIERE cannot assign SUPER_ADMIN");

                case RESPONSABLE_MATIERE -> {
                    if (dto.getMatiereId() == null
                            || !actingScopes.contains(dto.getMatiereId())) {
                        throw new UnauthorizedDelegationException(
                                "RESPONSABLE_MATIERE may only assign RESPONSABLE_MATIERE " +
                                "within their own matiereId scope. Attempted: "
                                + dto.getMatiereId());
                    }
                }

                case EVALUATEUR -> {
                    // EVALUATEUR always has null matiereId — no extra scope check needed.
                    // A RESPONSABLE_MATIERE may assign an EVALUATEUR (global) since that is
                    // the primary use-case: designating exam evaluators for their subject.
                }
            }
        }
    }

    /**
     * Extracts all matiereId values from the acting user's scoped authorities.
     *
     * <p>Example: authorities ["ROLE_RESPONSABLE_MATIERE:3", "ROLE_RESPONSABLE_MATIERE:7"]
     * returns Set{3, 7}.
     *
     * <p>Returns an empty set when the user has no RESPONSABLE_MATIERE authority
     * (i.e. they are an EVALUATEUR or have no relevant role).
     */
    private Set<Long> getActingUserScope(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith(RESPONSABLE_PREFIX))
                .map(a -> Long.parseLong(a.substring(RESPONSABLE_PREFIX.length())))
                .collect(Collectors.toSet());
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private User findUserOrThrow(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(
                        "User not found: " + userId));
    }

    private List<UserRole> buildUserRoles(User user, List<RoleAssignmentDto> dtos) {
        return dtos.stream()
                .map(dto -> UserRole.builder()
                        .user(user)
                        .role(dto.getRole())
                        .matiereId(dto.getMatiereId())
                        .build())
                .toList();
    }

    private UserResponse toResponse(User user) {
        List<UserRole> roles = userRoleRepository.findByUserId(user.getId());
        return toResponse(user, roles);
    }

    private UserResponse toResponse(User user, List<UserRole> roles) {
        return UserResponse.builder()
                .id(user.getId())
                .email(user.getEmail())
                .nom(user.getNom())
                .prenom(user.getPrenom())
                .isActive(user.getIsActive())
                .createdAt(user.getCreatedAt())
                .roles(roles.stream()
                        .map(r -> RoleAssignmentDto.builder()
                                .role(r.getRole())
                                .matiereId(r.getMatiereId())
                                .build())
                        .toList())
                .build();
    }

    private String describeRoles(List<UserRole> roles) {
        return roles.stream()
                .map(r -> r.getRole().name()
                        + (r.getMatiereId() != null ? ":" + r.getMatiereId() : ""))
                .collect(Collectors.joining(", "));
    }
}
