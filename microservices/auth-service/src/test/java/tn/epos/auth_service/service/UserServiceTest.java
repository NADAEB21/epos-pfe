package tn.epos.auth_service.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import tn.epos.auth_service.audit.AuditAction;
import tn.epos.auth_service.audit.AuditService;
import tn.epos.auth_service.dto.RoleAssignmentDto;
import tn.epos.auth_service.dto.UserCreateRequest;
import tn.epos.auth_service.entity.Matiere;
import tn.epos.auth_service.entity.RoleType;
import tn.epos.auth_service.entity.User;
import tn.epos.auth_service.entity.UserRole;
import tn.epos.auth_service.exception.MatiereNonAssignableException;
import tn.epos.auth_service.exception.UnauthorizedDelegationException;
import tn.epos.auth_service.repository.MatiereRepository;
import tn.epos.auth_service.repository.RefreshTokenRepository;
import tn.epos.auth_service.repository.UserRepository;
import tn.epos.auth_service.repository.UserRoleRepository;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private UserRoleRepository userRoleRepository;
    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private MatiereRepository matiereRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private AuditService auditService;

    /** #289 — la date du retrait est une donnée, pas un hasard : horloge fixe. */
    @Spy private java.time.Clock clock =
            java.time.Clock.fixed(java.time.Instant.parse("2026-08-04T09:00:00Z"),
                    java.time.ZoneId.of("UTC"));

    @InjectMocks private UserService userService;

    /**
     * #134 — par défaut, toute matière référencée par un test existe et est
     * active ; les tests de la garde catalogue redéfinissent ce stub.
     */
    @org.junit.jupiter.api.BeforeEach
    void stubCatalogueParDefaut() {
        lenient().when(matiereRepository.findById(anyLong())).thenAnswer(inv ->
                Optional.of(Matiere.builder()
                        .id(inv.getArgument(0)).code("M" + inv.getArgument(0))
                        .libelle("Matière " + inv.getArgument(0)).build()));
    }

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
    // #216 — target authorization: a RESPONSABLE_MATIERE must not be able to
    // rewrite/strip roles on accounts outside their authority (esp. SUPER_ADMIN).
    // =========================================================================

    @Test
    void assignRoles_responsable_cannotDemoteSuperAdmin_throws() {
        // The filed PoC: responsable:3 PUTs [EVALUATEUR] on a SUPER_ADMIN's id,
        // which would compute toRemove=[SUPER_ADMIN] and delete it.
        User target = existingUser(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(target));
        when(userRoleRepository.findByUserId(1L)).thenReturn(List.of(
                UserRole.builder().user(target).role(RoleType.SUPER_ADMIN).build()));

        assertThatThrownBy(() -> userService.assignRoles(1L,
                List.of(RoleAssignmentDto.builder().role(RoleType.EVALUATEUR).build()),
                authWith("ROLE_RESPONSABLE_MATIERE:3")))
                .isExactlyInstanceOf(UnauthorizedDelegationException.class)
                .hasMessageContaining("SUPER_ADMIN");

        verify(userRoleRepository, never()).deleteAll(any());
        verify(userRoleRepository, never()).saveAll(any());
    }

    @Test
    void assignRoles_responsable_cannotStripRoleOutsideScope_throws() {
        // Target is RESPONSABLE_MATIERE:7 (another matière). Caller responsable:3
        // PUTs [EVALUATEUR] -> toRemove=[RESPONSABLE:7], a role they could not assign.
        User target = existingUser(2L);
        when(userRepository.findById(2L)).thenReturn(Optional.of(target));
        when(userRoleRepository.findByUserId(2L)).thenReturn(List.of(
                UserRole.builder().user(target).role(RoleType.RESPONSABLE_MATIERE).matiereId(7L).build()));

        assertThatThrownBy(() -> userService.assignRoles(2L,
                List.of(RoleAssignmentDto.builder().role(RoleType.EVALUATEUR).build()),
                authWith("ROLE_RESPONSABLE_MATIERE:3")))
                .isExactlyInstanceOf(UnauthorizedDelegationException.class)
                .hasMessageContaining("7");

        verify(userRoleRepository, never()).deleteAll(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void assignRoles_responsable_canManageEvaluatorTargetInScope() {
        // Legitimate flow must still work: responsable:3 promotes an evaluator in
        // their own matière (adds RESPONSABLE_MATIERE:3, keeps EVALUATEUR).
        User target = existingUser(5L);
        when(userRepository.findById(5L)).thenReturn(Optional.of(target));
        when(userRoleRepository.findByUserId(5L)).thenReturn(List.of(
                UserRole.builder().user(target).role(RoleType.EVALUATEUR).build()));

        userService.assignRoles(5L, List.of(
                RoleAssignmentDto.builder().role(RoleType.EVALUATEUR).build(),
                RoleAssignmentDto.builder().role(RoleType.RESPONSABLE_MATIERE).matiereId(3L).build()),
                authWith("ROLE_RESPONSABLE_MATIERE:3"));

        verify(userRoleRepository, never()).deleteAll(any());
        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(userRoleRepository).saveAll(captor.capture());
        List<UserRole> saved = (List<UserRole>) captor.getValue();
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getRole()).isEqualTo(RoleType.RESPONSABLE_MATIERE);
        assertThat(saved.get(0).getMatiereId()).isEqualTo(3L);
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

    @Test
    void addRoles_responsable_cannotAddRoleToSuperAdmin_throws() {
        // #216 — the additive endpoint shares the target guard: a responsable
        // cannot touch a SUPER_ADMIN account even to add a role.
        User target = existingUser(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(target));
        when(userRoleRepository.findByUserId(1L)).thenReturn(List.of(
                UserRole.builder().user(target).role(RoleType.SUPER_ADMIN).build()));

        assertThatThrownBy(() -> userService.addRoles(1L,
                List.of(RoleAssignmentDto.builder().role(RoleType.EVALUATEUR).build()),
                authWith("ROLE_RESPONSABLE_MATIERE:3")))
                .isExactlyInstanceOf(UnauthorizedDelegationException.class)
                .hasMessageContaining("SUPER_ADMIN");

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
    void deactivateUser_softDeletes_revokesTokens_andRecordsWhoAndWhy() {
        User user = existingUser(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenReturn(user);
        when(userRoleRepository.findByUserId(1L)).thenReturn(List.of());

        userService.deactivateUser(1L, "Depart de la faculte", 99L);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        User saved = userCaptor.getValue();
        assertThat(saved.getIsActive()).isFalse();
        // #289 — l'acte se raconte : qui, quand, pourquoi
        assertThat(saved.getDeactivationMotif()).isEqualTo("Depart de la faculte");
        assertThat(saved.getDeactivatedBy()).isEqualTo(99L);
        assertThat(saved.getDeactivatedAt()).isNotNull();

        verify(refreshTokenRepository).revokeAllByUserId(1L);
        verify(auditService).logAttribue(eq(1L), anyString(), eq(AuditAction.USER_DEACTIVATED),
                eq("Depart de la faculte"), isNull(), eq(99L));
    }

    @Test
    void deactivateUser_refusesSelfRemoval() {
        // Le geste le plus banal et le plus couteux : se fermer soi-meme.
        User user = existingUser(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> userService.deactivateUser(1L, "erreur", 1L))
                .isInstanceOf(UnauthorizedDelegationException.class)
                .hasMessageContaining("votre propre");

        verify(userRepository, never()).save(any());
    }

    @Test
    void deactivateUser_refusesTheLastActiveSuperAdmin() {
        // Sans cette garde, la plateforme devient ingerable et la seule sortie
        // est une requete en base.
        User admin = existingUser(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(admin));
        when(userRoleRepository.findByUserId(1L)).thenReturn(
                List.of(UserRole.builder().role(RoleType.SUPER_ADMIN).build()));
        when(userRepository.findByRole(RoleType.SUPER_ADMIN)).thenReturn(List.of(admin));

        assertThatThrownBy(() -> userService.deactivateUser(1L, "menage", 42L))
                .isInstanceOf(UnauthorizedDelegationException.class)
                .hasMessageContaining("dernier administrateur");

        verify(userRepository, never()).save(any());
    }

    @Test
    void deactivateUser_allowsAnAdminWhenAnotherOneRemains() {
        User admin = existingUser(1L);
        User autre = existingUser(2L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(admin));
        when(userRoleRepository.findByUserId(1L)).thenReturn(
                List.of(UserRole.builder().role(RoleType.SUPER_ADMIN).build()));
        when(userRepository.findByRole(RoleType.SUPER_ADMIN)).thenReturn(List.of(admin, autre));
        when(userRepository.save(any(User.class))).thenReturn(admin);

        userService.deactivateUser(1L, "depart", 42L);

        verify(userRepository).save(any(User.class));
    }

    @Test
    void reactivateUser_reopensAndClearsAnyResidualLock() {
        // #289 + #294 — un compte qu'on rouvre ne doit pas heriter d'un verrou
        // vieux de six mois.
        User user = existingUser(1L);
        user.setIsActive(false);
        user.setDeactivationMotif("erreur de ligne");
        user.setLockedUntil(java.time.LocalDateTime.now(clock).plusMinutes(30));
        user.setLockCount(3);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenReturn(user);

        userService.reactivateUser(1L, "Homonyme : mauvaise ligne", 99L);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        User saved = captor.getValue();
        assertThat(saved.getIsActive()).isTrue();
        assertThat(saved.getDeactivationMotif()).isNull();
        assertThat(saved.getDeactivatedBy()).isNull();
        assertThat(saved.getLockedUntil()).isNull();
        assertThat(saved.getLockCount()).isZero();

        verify(auditService).logAttribue(eq(1L), anyString(), eq(AuditAction.USER_REACTIVATED),
                anyString(), isNull(), eq(99L));
    }

    // =========================================================================
    // #134 — garde catalogue : une nomination vise une matière existante et active
    // =========================================================================

    @Test
    void createUser_matiereInexistante_throws400PasUn500() {
        // Avant la garde : violation de FK -> 500 brut. La requête est
        // invalide, la réponse doit le dire nominativement.
        when(matiereRepository.findById(9999L)).thenReturn(Optional.empty());
        var req = createRequest("new@test.com", List.of(
                RoleAssignmentDto.builder()
                        .role(RoleType.RESPONSABLE_MATIERE).matiereId(9999L).build()));
        when(userRepository.existsByEmail("new@test.com")).thenReturn(false);

        assertThatThrownBy(() -> userService.createUser(req, authWith("ROLE_SUPER_ADMIN")))
                .isInstanceOf(MatiereNonAssignableException.class)
                .hasMessageContaining("9999");

        verify(userRepository, never()).save(any());
    }

    @Test
    void createUser_matiereRetiree_refuseNominativement() {
        when(matiereRepository.findById(3L)).thenReturn(Optional.of(
                Matiere.builder().id(3L).code("PHAG").libelle("Pharmacognosie")
                        .active(false).build()));
        var req = createRequest("new@test.com", List.of(
                RoleAssignmentDto.builder()
                        .role(RoleType.RESPONSABLE_MATIERE).matiereId(3L).build()));
        when(userRepository.existsByEmail("new@test.com")).thenReturn(false);

        assertThatThrownBy(() -> userService.createUser(req, authWith("ROLE_SUPER_ADMIN")))
                .isInstanceOf(MatiereNonAssignableException.class)
                .hasMessageContaining("Pharmacognosie")
                .hasMessageContaining("retirée");

        verify(userRepository, never()).save(any());
    }

    @Test
    void addRoles_evaluateurGlobal_passeSansConsulterLeCatalogue() {
        // EVALUATEUR porte matiereId null : rien à contrôler côté catalogue.
        User user = existingUser(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRoleRepository.findByUserId(1L)).thenReturn(List.of());

        userService.addRoles(1L, List.of(
                        RoleAssignmentDto.builder().role(RoleType.EVALUATEUR).build()),
                authWith("ROLE_SUPER_ADMIN"));

        verify(userRoleRepository).saveAll(any());
        verify(matiereRepository, never()).findById(any());
    }

    @Test
    void addRoles_compteDejaPorteurDuneMatiereRetiree_resteModifiable() {
        // Le rôle CONSERVÉ sur une matière retirée n'est pas une nouvelle
        // nomination : ajouter EVALUATEUR à ce compte doit passer.
        User user = existingUser(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRoleRepository.findByUserId(1L)).thenReturn(List.of(
                UserRole.builder().role(RoleType.RESPONSABLE_MATIERE).matiereId(3L).build()));

        userService.addRoles(1L, List.of(
                        RoleAssignmentDto.builder().role(RoleType.EVALUATEUR).build()),
                authWith("ROLE_SUPER_ADMIN"));

        // La matière 3 (retirée ou non) n'est jamais re-contrôlée : seul
        // l'AJOUT est soumis à la garde, et il ne référence aucune matière.
        verify(matiereRepository, never()).findById(any());
        verify(userRoleRepository).saveAll(any());
    }
}
