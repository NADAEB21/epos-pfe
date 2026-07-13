package tn.epos.auth_service.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import tn.epos.auth_service.audit.AuditService;
import tn.epos.auth_service.dto.RoleAssignmentDto;
import tn.epos.auth_service.dto.UserCreateRequest;
import tn.epos.auth_service.entity.RoleType;
import tn.epos.auth_service.entity.User;
import tn.epos.auth_service.entity.UserRole;
import tn.epos.auth_service.exception.UnauthorizedDelegationException;
import tn.epos.auth_service.repository.RefreshTokenRepository;
import tn.epos.auth_service.repository.UserRepository;
import tn.epos.auth_service.repository.UserRoleRepository;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private UserRoleRepository userRoleRepository;
    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private AuditService auditService;

    @InjectMocks private UserService userService;

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    private User existingUser(Long id) {
        return User.builder()
                .id(id).email("user" + id + "@test.com")
                .nom("Doe").prenom("John")
                .isActive(true).failedLoginAttempts(0)
                .build();
    }

    /**
     * Creates a mock Authentication whose authorities include the given authority strings.
     * doReturn avoids the unchecked-cast issue with Collection<? extends GrantedAuthority>.
     */
    private Authentication authWith(String... authorityStrings) {
        Authentication auth = mock(Authentication.class);
        List<SimpleGrantedAuthority> authorities = java.util.Arrays.stream(authorityStrings)
                .map(SimpleGrantedAuthority::new)
                .toList();
        doReturn(authorities).when(auth).getAuthorities();
        return auth;
    }

    /**
     * Creates a mock UserCreateRequest — the class has no builder/all-args constructor.
     */
    private UserCreateRequest createRequest(String email, List<RoleAssignmentDto> roles) {
        UserCreateRequest req = mock(UserCreateRequest.class);
        lenient().when(req.getEmail()).thenReturn(email);
        lenient().when(req.getPassword()).thenReturn("Password1");
        lenient().when(req.getNom()).thenReturn("Doe");
        lenient().when(req.getPrenom()).thenReturn("John");
        lenient().when(req.getRoles()).thenReturn(roles);
        return req;
    }

    // =========================================================================
    // getAllUsers() — optional role filter (#80)
    // =========================================================================

    @Test
    void getAllUsers_noFilter_returnsAll() {
        when(userRepository.findAll()).thenReturn(List.of(existingUser(1L), existingUser(2L)));
        when(userRoleRepository.findByUserId(anyLong())).thenReturn(List.of());

        var result = userService.getAllUsers(null);

        assertThat(result).hasSize(2);
        verify(userRepository).findAll();
        verify(userRepository, never()).findByRole(any());
    }

    @Test
    void getAllUsers_withRoleFilter_delegatesToFindByRole() {
        // RESPONSABLE_MATIERE picker calls /api/v1/users?role=EVALUATEUR
        when(userRepository.findByRole(RoleType.EVALUATEUR))
                .thenReturn(List.of(existingUser(7L)));
        when(userRoleRepository.findByUserId(7L)).thenReturn(List.of(
                UserRole.builder().role(RoleType.EVALUATEUR).build()));

        var result = userService.getAllUsers(RoleType.EVALUATEUR);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo(7L);
        verify(userRepository).findByRole(RoleType.EVALUATEUR);
        verify(userRepository, never()).findAll();
    }

    @Test
    void getAllUsers_withRoleFilter_emptyResultIsOk() {
        when(userRepository.findByRole(RoleType.SUPER_ADMIN)).thenReturn(List.of());

        var result = userService.getAllUsers(RoleType.SUPER_ADMIN);

        assertThat(result).isEmpty();
    }

    // =========================================================================
    // createUser() — delegation checks
    // =========================================================================

    @Test
    void createUser_superAdmin_canCreateAnyRole() {
        Authentication auth = authWith("ROLE_SUPER_ADMIN");
        List<RoleAssignmentDto> roles = List.of(
                RoleAssignmentDto.builder().role(RoleType.SUPER_ADMIN).build());

        when(userRepository.existsByEmail("new@test.com")).thenReturn(false);
        User saved = existingUser(10L);
        when(userRepository.save(any(User.class))).thenReturn(saved);
        when(passwordEncoder.encode("Password1")).thenReturn("hashed");
        when(userRoleRepository.saveAll(any())).thenReturn(List.of());

        assertThatNoException().isThrownBy(() ->
                userService.createUser(createRequest("new@test.com", roles), auth));

        verify(userRepository).save(any(User.class));
        verify(userRoleRepository).saveAll(any());
    }

    @Test
    void createUser_responsable_canCreateWithinScope() {
        // RESPONSABLE_MATIERE for matiere 3 may create another RESPONSABLE_MATIERE in matiere 3
        Authentication auth = authWith("ROLE_RESPONSABLE_MATIERE:3");
        List<RoleAssignmentDto> roles = List.of(
                RoleAssignmentDto.builder().role(RoleType.RESPONSABLE_MATIERE).matiereId(3L).build());

        when(userRepository.existsByEmail("scoped@test.com")).thenReturn(false);
        User saved = existingUser(11L);
        when(userRepository.save(any(User.class))).thenReturn(saved);
        when(passwordEncoder.encode("Password1")).thenReturn("hashed");
        when(userRoleRepository.saveAll(any())).thenReturn(List.of());

        assertThatNoException().isThrownBy(() ->
                userService.createUser(createRequest("scoped@test.com", roles), auth));

        verify(userRepository).save(any(User.class));
    }

    @Test
    void createUser_responsable_cannotCreateOutsideScope_throws() {
        // RESPONSABLE_MATIERE for matiere 3 must not create roles scoped to matiere 7
        Authentication auth = authWith("ROLE_RESPONSABLE_MATIERE:3");
        List<RoleAssignmentDto> roles = List.of(
                RoleAssignmentDto.builder().role(RoleType.RESPONSABLE_MATIERE).matiereId(7L).build());

        when(userRepository.existsByEmail("outsider@test.com")).thenReturn(false);

        assertThatThrownBy(() ->
                userService.createUser(createRequest("outsider@test.com", roles), auth))
                .isExactlyInstanceOf(UnauthorizedDelegationException.class)
                .hasMessageContaining("7");

        verify(userRepository, never()).save(any());
    }

    @Test
    void createUser_evaluateur_cannotDelegate_throws() {
        // EVALUATEUR has zero delegation rights
        Authentication auth = authWith("ROLE_EVALUATEUR");
        List<RoleAssignmentDto> roles = List.of(
                RoleAssignmentDto.builder().role(RoleType.EVALUATEUR).build());

        when(userRepository.existsByEmail("eval@test.com")).thenReturn(false);

        assertThatThrownBy(() ->
                userService.createUser(createRequest("eval@test.com", roles), auth))
                .isExactlyInstanceOf(UnauthorizedDelegationException.class)
                .hasMessageContaining("EVALUATEUR");

        verify(userRepository, never()).save(any());
    }

    @Test
    void createUser_responsable_cannotCreateSuperAdmin_throws() {
        // RESPONSABLE_MATIERE must never be allowed to mint a SUPER_ADMIN
        Authentication auth = authWith("ROLE_RESPONSABLE_MATIERE:3");
        List<RoleAssignmentDto> roles = List.of(
                RoleAssignmentDto.builder().role(RoleType.SUPER_ADMIN).build());

        when(userRepository.existsByEmail("admin@test.com")).thenReturn(false);

        assertThatThrownBy(() ->
                userService.createUser(createRequest("admin@test.com", roles), auth))
                .isExactlyInstanceOf(UnauthorizedDelegationException.class)
                .hasMessageContaining("SUPER_ADMIN");

        verify(userRepository, never()).save(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void createUser_dedupesRepeatedRoles() {
        Authentication auth = authWith("ROLE_SUPER_ADMIN");
        List<RoleAssignmentDto> roles = List.of(
                RoleAssignmentDto.builder().role(RoleType.EVALUATEUR).build(),
                RoleAssignmentDto.builder().role(RoleType.EVALUATEUR).build());

        when(userRepository.existsByEmail("dup@test.com")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenReturn(existingUser(12L));
        when(passwordEncoder.encode("Password1")).thenReturn("hashed");
        when(userRoleRepository.saveAll(any())).thenReturn(List.of());

        userService.createUser(createRequest("dup@test.com", roles), auth);

        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(userRoleRepository).saveAll(captor.capture());
        assertThat((List<UserRole>) captor.getValue()).hasSize(1);
    }

    // =========================================================================
    // assignRoles() — PUT semantics: full replace
    // =========================================================================

    @Test
    @SuppressWarnings("unchecked")
    void assignRoles_replacesExistingRoles() {
        User user = existingUser(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        // Old role: EVALUATEUR
        List<UserRole> oldRoles = List.of(
                UserRole.builder().user(user).role(RoleType.EVALUATEUR).build());
        when(userRoleRepository.findByUserId(1L)).thenReturn(oldRoles);

        // New role: RESPONSABLE_MATIERE:5
        List<RoleAssignmentDto> newRoleDtos = List.of(
                RoleAssignmentDto.builder().role(RoleType.RESPONSABLE_MATIERE).matiereId(5L).build());

        Authentication auth = authWith("ROLE_SUPER_ADMIN");

        userService.assignRoles(1L, newRoleDtos, auth);

        // The role that is no longer wanted is deleted — and ONLY that one.
        ArgumentCaptor<List> removedCaptor = ArgumentCaptor.forClass(List.class);
        verify(userRoleRepository).deleteAll(removedCaptor.capture());
        List<UserRole> removed = (List<UserRole>) removedCaptor.getValue();
        assertThat(removed).hasSize(1);
        assertThat(removed.get(0).getRole()).isEqualTo(RoleType.EVALUATEUR);

        // Verify the exact new roles that were persisted
        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(userRoleRepository).saveAll(captor.capture());
        List<UserRole> saved = (List<UserRole>) captor.getValue();
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getRole()).isEqualTo(RoleType.RESPONSABLE_MATIERE);
        assertThat(saved.get(0).getMatiereId()).isEqualTo(5L);

        // Both REVOKED (old) and ASSIGNED (new) audit entries must be logged
        verify(auditService, times(2)).log(
                eq(1L), eq("user1@test.com"), any(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void assignRoles_keepsUnchangedRoleInPlace_neverDeletesAndReinsertsIt() {
        // A RESPONSABLE_MATIERE:1 who is ALSO made EVALUATEUR. The responsable row
        // must be left untouched: deleting and re-inserting the same (user, role,
        // matiere) key inside one transaction is what trips the unique index when
        // Hibernate orders the INSERT before the DELETE.
        User user = existingUser(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        when(userRoleRepository.findByUserId(1L)).thenReturn(List.of(
                UserRole.builder().user(user).role(RoleType.RESPONSABLE_MATIERE).matiereId(1L).build()));

        List<RoleAssignmentDto> desired = List.of(
                RoleAssignmentDto.builder().role(RoleType.RESPONSABLE_MATIERE).matiereId(1L).build(),
                RoleAssignmentDto.builder().role(RoleType.EVALUATEUR).build());

        userService.assignRoles(1L, desired, authWith("ROLE_SUPER_ADMIN"));

        // Nothing removed — the responsable role survives
        verify(userRoleRepository, never()).deleteAll(any());
        verify(userRoleRepository, never()).deleteByUserId(anyLong());

        // Only the genuinely new EVALUATEUR row is inserted
        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(userRoleRepository).saveAll(captor.capture());
        List<UserRole> saved = (List<UserRole>) captor.getValue();
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getRole()).isEqualTo(RoleType.EVALUATEUR);
        assertThat(saved.get(0).getMatiereId()).isNull();
    }

    // =========================================================================
    // addRoles() — POST semantics: additive, idempotent
    // =========================================================================

    @Test
    @SuppressWarnings("unchecked")
    void addRoles_addsEvaluateur_withoutRevokingResponsable() {
        // The supervisor's scenario: a RESPONSABLE_MATIERE must also evaluate.
        User user = existingUser(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        when(userRoleRepository.findByUserId(1L)).thenReturn(List.of(
                UserRole.builder().user(user).role(RoleType.RESPONSABLE_MATIERE).matiereId(1L).build()));

        userService.addRoles(1L,
                List.of(RoleAssignmentDto.builder().role(RoleType.EVALUATEUR).build()),
                authWith("ROLE_SUPER_ADMIN"));

        // The existing responsable role is NEVER touched by an additive call
        verify(userRoleRepository, never()).deleteAll(any());
        verify(userRoleRepository, never()).deleteByUserId(anyLong());

        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(userRoleRepository).saveAll(captor.capture());
        List<UserRole> saved = (List<UserRole>) captor.getValue();
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getRole()).isEqualTo(RoleType.EVALUATEUR);
    }

    @Test
    void addRoles_isIdempotent_reAddingHeldRoleWritesNothing() {
        User user = existingUser(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        when(userRoleRepository.findByUserId(1L)).thenReturn(List.of(
                UserRole.builder().user(user).role(RoleType.EVALUATEUR).build()));

        userService.addRoles(1L,
                List.of(RoleAssignmentDto.builder().role(RoleType.EVALUATEUR).build()),
                authWith("ROLE_SUPER_ADMIN"));

        // No duplicate row, no spurious audit entry
        verify(userRoleRepository, never()).saveAll(any());
        verify(userRoleRepository, never()).deleteAll(any());
        verify(auditService, never()).log(anyLong(), any(), any(), any(), any());
    }

    @Test
    void addRoles_enforcesDelegation() {
        // A RESPONSABLE_MATIERE:3 must not use the additive endpoint to escalate
        User user = existingUser(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> userService.addRoles(1L,
                List.of(RoleAssignmentDto.builder().role(RoleType.SUPER_ADMIN).build()),
                authWith("ROLE_RESPONSABLE_MATIERE:3")))
                .isExactlyInstanceOf(UnauthorizedDelegationException.class);

        verify(userRoleRepository, never()).saveAll(any());
    }

    // =========================================================================
    // getMeResponse() — multi-role users
    // =========================================================================

    @Test
    void getMeResponse_listsEveryRole_andPicksPrimaryDeterministically() {
        User user = existingUser(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        // Deliberately EVALUATEUR-first: the old code returned roles.get(0), so the
        // "primary" role flipped with the row order Postgres happened to return.
        when(userRoleRepository.findByUserId(1L)).thenReturn(List.of(
                UserRole.builder().user(user).role(RoleType.EVALUATEUR).build(),
                UserRole.builder().user(user).role(RoleType.RESPONSABLE_MATIERE).matiereId(1L).build()));

        var me = userService.getMeResponse(1L);

        assertThat(me.getRole()).isEqualTo("RESPONSABLE_MATIERE");   // most privileged, not first
        assertThat(me.getRoles()).hasSize(2);
        assertThat(me.getRoles()).extracting(RoleAssignmentDto::getRole)
                .containsExactlyInAnyOrder(RoleType.EVALUATEUR, RoleType.RESPONSABLE_MATIERE);
    }

    @Test
    void getMeResponse_roleless_defaultsToEvaluateur() {
        User user = existingUser(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRoleRepository.findByUserId(1L)).thenReturn(List.of());

        var me = userService.getMeResponse(1L);

        assertThat(me.getRole()).isEqualTo("EVALUATEUR");
        assertThat(me.getRoles()).isEmpty();
    }

    // =========================================================================
    // deactivateUser()
    // =========================================================================

    @Test
    void deactivateUser_softDeletes_revokesTokens() {
        User user = existingUser(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenReturn(user);

        userService.deactivateUser(1L);

        // isActive must be set to false on the entity before saving
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getIsActive()).isFalse();

        // All active sessions must be revoked immediately
        verify(refreshTokenRepository).revokeAllByUserId(1L);
    }
}
