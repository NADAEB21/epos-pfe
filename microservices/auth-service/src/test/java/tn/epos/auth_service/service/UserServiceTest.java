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

    // =========================================================================
    // assignRoles()
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

        // Old roles must be wiped first
        verify(userRoleRepository).deleteByUserId(1L);

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
