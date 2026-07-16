package tn.epos.auth_service.service;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.epos.auth_service.audit.AuditAction;
import tn.epos.auth_service.audit.AuditService;
import tn.epos.auth_service.dto.MeResponse;
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

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserService {

    private static final String RESPONSABLE_PREFIX = "ROLE_RESPONSABLE_MATIERE:";

    /** Privilège décroissant — sert à choisir le rôle principal exposé par /auth/me. */
    private static final List<RoleType> ROLE_PRECEDENCE = List.of(
            RoleType.SUPER_ADMIN, RoleType.RESPONSABLE_MATIERE, RoleType.EVALUATEUR);

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    // -------------------------------------------------------------------------
    // Read
    // -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<UserResponse> getAllUsers(RoleType filter) {
        List<User> users = (filter == null)
                ? userRepository.findAll()
                : userRepository.findByRole(filter);
        return users.stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Construit le MeResponse pour GET /api/v1/auth/me.
     *
     * <p>Un utilisateur peut cumuler des rôles (un RESPONSABLE_MATIERE est
     * souvent aussi EVALUATEUR). La réponse porte donc la liste complète.
     * Le champ {@code role} reste exposé pour compatibilité, mais il est choisi
     * de façon déterministe par privilège décroissant — l'ancien code prenait
     * {@code roles.get(0)}, dont l'ordre n'est pas garanti par la requête.
     */
    @Transactional(readOnly = true)
    public MeResponse getMeResponse(Long userId) {
        User user = findUserOrThrow(userId);
        List<UserRole> roles = userRoleRepository.findByUserId(userId);

        return MeResponse.builder()
                .id(user.getId())
                .email(user.getEmail())
                .nom(user.getNom())
                .prenom(user.getPrenom())
                .role(primaryRole(roles).name())
                .roles(roles.stream()
                        .map(r -> RoleAssignmentDto.builder()
                                .role(r.getRole())
                                .matiereId(r.getMatiereId())
                                .build())
                        .toList())
                .build();
    }

    /**
     * Rôle principal : le plus privilégié des rôles portés par l'utilisateur.
     * Un utilisateur sans aucun rôle est traité comme EVALUATEUR (cas limite :
     * compte créé sans rôle — il n'a de toute façon accès à rien).
     */
    private RoleType primaryRole(List<UserRole> roles) {
        return roles.stream()
                .map(UserRole::getRole)
                .min(Comparator.comparingInt(ROLE_PRECEDENCE::indexOf))
                .orElse(RoleType.EVALUATEUR);
    }

    // -------------------------------------------------------------------------
    // Create
    // -------------------------------------------------------------------------

    @Transactional
    public UserResponse createUser(UserCreateRequest request, Authentication authentication) {
        if (userRepository.existsByEmail(request.getEmail())) {
            // Une adresse e-mail identifie UNE personne, pas UN rôle. Un
            // responsable qui doit aussi évaluer ne se recrée pas : on lui ajoute
            // le rôle EVALUATEUR sur son compte existant.
            throw new EmailAlreadyExistsException(
                    "Email already in use: " + request.getEmail()
                    + ". A user holds several roles on a single account — to also make "
                    + "this person an EVALUATEUR, call POST /api/v1/users/{id}/roles "
                    + "instead of creating a second account.");
        }

        List<RoleAssignmentDto> requestedRoles = dedupe(request.getRoles());
        validateDelegation(requestedRoles, authentication);

        User user = User.builder()
                .email(request.getEmail())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .nom(request.getNom())
                .prenom(request.getPrenom())
                .isActive(true)
                .failedLoginAttempts(0)
                .build();
        user = userRepository.save(user);

        List<UserRole> roles = buildUserRoles(user, requestedRoles);
        userRoleRepository.saveAll(roles);

        auditService.log(user.getId(), user.getEmail(), AuditAction.USER_CREATED,
                describeRoles(roles), null);

        return toResponse(user, roles);
    }

    // -------------------------------------------------------------------------
    // Role assignment
    // -------------------------------------------------------------------------

    /**
     * PUT /users/{id}/roles — remplace intégralement la liste des rôles.
     *
     * <p>Les rôles déjà présents et toujours demandés sont laissés en place plutôt
     * que supprimés puis réinsérés : cela évite un DELETE + INSERT sur la même clé
     * dans une seule transaction, que Hibernate peut réordonner (INSERT avant
     * DELETE) et qui violerait l'index unique de {@code user_roles}.
     */
    @Transactional
    public void assignRoles(Long userId, List<RoleAssignmentDto> newRoleDtos,
                            Authentication authentication) {
        User user = findUserOrThrow(userId);

        List<RoleAssignmentDto> desired = dedupe(newRoleDtos);
        validateDelegation(desired, authentication);

        List<UserRole> existing = userRoleRepository.findByUserId(userId);
        // #216 : autoriser aussi la CIBLE — un appelant non SUPER_ADMIN ne peut
        // pas modifier un compte SUPER_ADMIN (empêche la rétrogradation d'un
        // super-admin via ce PUT à remplacement intégral).
        validateTargetModifiable(existing, authentication);

        Set<String> desiredKeys = desired.stream().map(this::roleKey).collect(Collectors.toSet());
        Set<String> existingKeys = existing.stream().map(this::roleKey).collect(Collectors.toSet());

        List<UserRole> toRemove = existing.stream()
                .filter(r -> !desiredKeys.contains(roleKey(r)))
                .toList();
        // #216 : un rôle RETIRÉ doit être un rôle que l'appelant aurait pu
        // attribuer — sinon un responsable pourrait dépouiller un rôle hors de
        // son périmètre (p. ex. le RESPONSABLE_MATIERE d'une autre matière).
        validateDelegation(toRoleDtos(toRemove), authentication);

        List<UserRole> toAdd = buildUserRoles(user, desired.stream()
                .filter(d -> !existingKeys.contains(roleKey(d)))
                .toList());

        applyRoleDelta(user, toRemove, toAdd);
    }

    /**
     * POST /users/{id}/roles — ajoute des rôles sans toucher aux rôles existants.
     *
     * <p>Endpoint distinct du PUT parce que l'opération courante ("ce responsable
     * évaluera aussi cet examen") ne doit pas pouvoir détruire silencieusement son
     * rôle RESPONSABLE_MATIERE si l'appelant oublie de le renvoyer.
     * Idempotent : réajouter un rôle déjà porté ne crée pas de doublon.
     */
    @Transactional
    public void addRoles(Long userId, List<RoleAssignmentDto> roleDtos,
                         Authentication authentication) {
        User user = findUserOrThrow(userId);

        List<RoleAssignmentDto> requested = dedupe(roleDtos);
        validateDelegation(requested, authentication);

        List<UserRole> existing = userRoleRepository.findByUserId(userId);
        // #216 : même garde côté cible que le PUT — un responsable ne peut pas
        // ajouter de rôle sur un compte SUPER_ADMIN.
        validateTargetModifiable(existing, authentication);
        Set<String> existingKeys = existing.stream()
                .map(this::roleKey)
                .collect(Collectors.toSet());

        List<UserRole> toAdd = buildUserRoles(user, requested.stream()
                .filter(d -> !existingKeys.contains(roleKey(d)))
                .toList());

        applyRoleDelta(user, List.of(), toAdd);
    }

    private void applyRoleDelta(User user, List<UserRole> toRemove, List<UserRole> toAdd) {
        if (!toRemove.isEmpty()) {
            userRoleRepository.deleteAll(toRemove);
            auditService.log(user.getId(), user.getEmail(), AuditAction.ROLE_REVOKED,
                    "Removed: " + describeRoles(toRemove), null);
        }
        if (!toAdd.isEmpty()) {
            userRoleRepository.saveAll(toAdd);
            auditService.log(user.getId(), user.getEmail(), AuditAction.ROLE_ASSIGNED,
                    "Assigned: " + describeRoles(toAdd), null);
        }
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
        if (isSuperAdmin(authentication)) {
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

    /** True si l'appelant porte l'autorité globale SUPER_ADMIN. */
    private boolean isSuperAdmin(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch("ROLE_SUPER_ADMIN"::equals);
    }

    /**
     * #216 — autorisation de la CIBLE. Un appelant non SUPER_ADMIN (donc
     * RESPONSABLE_MATIERE) ne peut pas modifier le jeu de rôles d'un compte
     * SUPER_ADMIN. Sans cette garde, un responsable pouvait rétrograder ou
     * neutraliser un super-admin via PUT|POST /users/{id}/roles : le contrôle de
     * délégation n'inspectait que la charge utile, jamais les rôles de la cible.
     */
    private void validateTargetModifiable(List<UserRole> targetExistingRoles,
                                          Authentication authentication) {
        if (isSuperAdmin(authentication)) {
            return; // un SUPER_ADMIN peut modifier n'importe quel compte
        }
        boolean targetIsSuperAdmin = targetExistingRoles.stream()
                .anyMatch(r -> r.getRole() == RoleType.SUPER_ADMIN);
        if (targetIsSuperAdmin) {
            throw new UnauthorizedDelegationException(
                    "RESPONSABLE_MATIERE cannot modify a SUPER_ADMIN account");
        }
    }

    /** #216 — projette des UserRole existants en DTO pour re-valider la délégation. */
    private List<RoleAssignmentDto> toRoleDtos(List<UserRole> roles) {
        return roles.stream()
                .map(r -> RoleAssignmentDto.builder()
                        .role(r.getRole())
                        .matiereId(r.getMatiereId())
                        .build())
                .toList();
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

    /**
     * Identité d'un rôle : (type, matiereId). Deux lignes user_roles partageant
     * cette clé pour un même user sont des doublons — cf. l'index unique
     * ux_user_roles_user_role_matiere dans init.sql.
     */
    private String roleKey(RoleType role, Long matiereId) {
        return role.name() + ":" + matiereId;
    }

    private String roleKey(UserRole role) {
        return roleKey(role.getRole(), role.getMatiereId());
    }

    private String roleKey(RoleAssignmentDto dto) {
        return roleKey(dto.getRole(), dto.getMatiereId());
    }

    /** Supprime les doublons de la charge utile, en conservant l'ordre d'appel. */
    private List<RoleAssignmentDto> dedupe(List<RoleAssignmentDto> dtos) {
        Map<String, RoleAssignmentDto> unique = new LinkedHashMap<>();
        for (RoleAssignmentDto dto : dtos) {
            unique.putIfAbsent(roleKey(dto), dto);
        }
        return List.copyOf(unique.values());
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
