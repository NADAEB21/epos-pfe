package tn.epos.auth_service.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import tn.epos.auth_service.audit.AuditAction;
import tn.epos.auth_service.audit.AuditService;
import tn.epos.auth_service.dto.ChangePasswordRequest;
import tn.epos.auth_service.dto.LoginRequest;
import tn.epos.auth_service.dto.LoginResponse;
import tn.epos.auth_service.dto.PasswordResetConfirmDto;
import tn.epos.auth_service.entity.PasswordResetToken;
import tn.epos.auth_service.entity.RefreshToken;
import tn.epos.auth_service.entity.User;
import tn.epos.auth_service.exception.AccountLockedException;
import tn.epos.auth_service.exception.InvalidTokenException;
import tn.epos.auth_service.repository.PasswordResetTokenRepository;
import tn.epos.auth_service.repository.RefreshTokenRepository;
import tn.epos.auth_service.repository.UserRepository;
import tn.epos.auth_service.repository.UserRoleRepository;
import tn.epos.auth_service.service.email.EmailService;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private UserRoleRepository userRoleRepository;
    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private PasswordResetTokenRepository passwordResetTokenRepository;
    @Mock private JwtService jwtService;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private AuditService auditService;
    @Mock private EmailService emailService;
    @Mock private TokenRevocationService tokenRevocationService;

    /**
     * #294 — horloge FIXE : le verrou temporaire est une décision datée, et un
     * test qui « attend deux minutes » n'est pas un test. On la substitue.
     */
    @Spy private Clock clock = Clock.fixed(Instant.parse("2026-08-04T09:00:00Z"), ZoneId.of("UTC"));

    @InjectMocks private AuthService authService;

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    private User activeUser() {
        return User.builder()
                .id(1L).email("user@test.com").passwordHash("hashed-pw")
                .nom("Test").prenom("User").isActive(true).failedLoginAttempts(0)
                .build();
    }

    /** Retiré par l'administration — état durable (#294). */
    private User deactivatedUser() {
        return User.builder()
                .id(1L).email("user@test.com").passwordHash("hashed-pw")
                .nom("Test").prenom("User").isActive(false).failedLoginAttempts(0)
                .build();
    }

    /** Verrou temporaire encore actif (#294) — le compte reste isActive=true. */
    private User temporarilyLockedUser(long minutesRemaining) {
        return User.builder()
                .id(1L).email("user@test.com").passwordHash("hashed-pw")
                .nom("Test").prenom("User").isActive(true).failedLoginAttempts(0)
                .lockedUntil(LocalDateTime.now(clock).plusMinutes(minutesRemaining))
                .lockCount(1)
                .build();
    }

    /** Creates a mocked LoginRequest — LoginRequest has no builder or all-args constructor. */
    private LoginRequest loginReq(String email, String password) {
        LoginRequest req = mock(LoginRequest.class);
        lenient().when(req.getEmail()).thenReturn(email);
        lenient().when(req.getPassword()).thenReturn(password);
        return req;
    }

    /** Stubs JwtService to return predictable tokens for the happy path. */
    private void stubTokenIssuance() {
        when(jwtService.generateAccessToken(any(), any())).thenReturn("access-token");
        when(jwtService.generateRefreshTokenValue()).thenReturn("raw-refresh");
        when(jwtService.hashToken("raw-refresh")).thenReturn("refresh-hash");
        when(jwtService.generateFamilyId()).thenReturn("family-id");
    }

    // =========================================================================
    // login()
    // =========================================================================

    @Test
    void login_success_returnsTokens() {
        User user = activeUser();
        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Password1", "hashed-pw")).thenReturn(true);
        when(userRoleRepository.findByUserId(1L)).thenReturn(List.of());
        stubTokenIssuance();

        LoginResponse response = authService.login(loginReq("user@test.com", "Password1"), "127.0.0.1");

        assertThat(response.getAccessToken()).isEqualTo("access-token");
        assertThat(response.getRefreshToken()).isEqualTo("raw-refresh");
    }

    @Test
    void login_wrongPassword_incrementsCounter() {
        User user = activeUser();
        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);
        when(userRepository.getFailedLoginAttempts(1L)).thenReturn(1);

        LoginRequest req = loginReq("user@test.com", "wrong");
        assertThatThrownBy(() -> authService.login(req, "127.0.0.1"))
                .isExactlyInstanceOf(BadCredentialsException.class);

        verify(userRepository).incrementFailedAttempts(1L);
        verify(userRepository, never()).applyTemporaryLock(any(), any());
    }

    @Test
    void login_secondFailure_counterIs2() {
        User user = activeUser();
        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);
        when(userRepository.getFailedLoginAttempts(1L)).thenReturn(2);

        LoginRequest req = loginReq("user@test.com", "wrong");
        assertThatThrownBy(() -> authService.login(req, "127.0.0.1"))
                .isExactlyInstanceOf(BadCredentialsException.class)
                .hasMessage("Invalid email or password");

        verify(userRepository).incrementFailedAttempts(1L);
        verify(userRepository, never()).applyTemporaryLock(any(), any());
    }

    @Test
    void login_thirdFailure_appliesTemporaryLock_notPermanent() {
        User user = activeUser();
        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);
        when(userRepository.getFailedLoginAttempts(1L)).thenReturn(3);
        when(userRepository.getLockCount(1L)).thenReturn(0);

        LoginRequest req = loginReq("user@test.com", "wrong");
        assertThatThrownBy(() -> authService.login(req, "127.0.0.1"))
                .isExactlyInstanceOf(AccountLockedException.class);

        verify(userRepository).incrementFailedAttempts(1L);
        // #294 — verrou TEMPORAIRE : 1er verrou = 2 min à partir de l'horloge fixe
        verify(userRepository).applyTemporaryLock(1L,
                LocalDateTime.now(clock).plusMinutes(2));
        verify(userRepository, never()).save(any());

        // Verify the audit log is fired with the correct locked-account action
        verify(auditService).log(
                eq(1L), eq("user@test.com"),
                eq(AuditAction.ACCOUNT_LOCKED),
                contains("3"),
                eq("127.0.0.1"));
    }

    @Test
    void login_temporaryLockStillRunning_refusesAndAnnouncesRemainingTime() {
        // #294 — le message doit dire QUAND réessayer : sans délai annoncé,
        // « verrouillé » est indiscernable de « mort ».
        // le fixture touche l'horloge (un @Spy) : le construire AVANT le when(),
        // sinon Mockito voit une interaction au milieu d'un stubbing.
        User locked = temporarilyLockedUser(5);
        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(locked));

        LoginRequest req = loginReq("user@test.com", "Password1");
        assertThatThrownBy(() -> authService.login(req, "127.0.0.1"))
                .isExactlyInstanceOf(AccountLockedException.class)
                .hasMessageContaining("temporairement")
                .hasMessageContaining("minute");

        // le mot de passe n'est même pas examiné
        verify(passwordEncoder, never()).matches(anyString(), anyString());
    }

    @Test
    void login_expiredTemporaryLock_letsTheUserBackIn() {
        // LE test de #294 : le verrou s'ouvre TOUT SEUL. lockedUntil est dans le
        // passé de l'horloge fixe — aucune intervention d'administrateur.
        User user = temporarilyLockedUser(-1);
        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Password1", "hashed-pw")).thenReturn(true);
        when(userRoleRepository.findByUserId(1L)).thenReturn(List.of());
        stubTokenIssuance();

        LoginResponse response = authService.login(loginReq("user@test.com", "Password1"), "127.0.0.1");

        assertThat(response.getAccessToken()).isEqualTo("access-token");
        // la connexion réussie efface compteur ET verrou (sinon l'escalade
        // continuerait pour quelqu'un qui a simplement retrouvé son mot de passe)
        verify(userRepository).resetFailedAttempts(1L);
    }

    @Test
    void login_repeatedLockouts_escalateTheDuration() {
        // 3e verrou consécutif ⇒ 2 · 2^2 = 8 minutes.
        User user = activeUser();
        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);
        when(userRepository.getFailedLoginAttempts(1L)).thenReturn(3);
        when(userRepository.getLockCount(1L)).thenReturn(2);

        LoginRequest req = loginReq("user@test.com", "wrong");
        assertThatThrownBy(() -> authService.login(req, "127.0.0.1"))
                .isExactlyInstanceOf(AccountLockedException.class);

        verify(userRepository).applyTemporaryLock(1L, LocalDateTime.now(clock).plusMinutes(8));
    }

    @Test
    void login_escalationIsCapped() {
        // Sans plafond, le backoff redeviendrait un verrou définitif de fait.
        User user = activeUser();
        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);
        when(userRepository.getFailedLoginAttempts(1L)).thenReturn(3);
        when(userRepository.getLockCount(1L)).thenReturn(9);

        LoginRequest req = loginReq("user@test.com", "wrong");
        assertThatThrownBy(() -> authService.login(req, "127.0.0.1"))
                .isExactlyInstanceOf(AccountLockedException.class);

        verify(userRepository).applyTemporaryLock(1L, LocalDateTime.now(clock).plusMinutes(30));
    }

    @Test
    void login_deactivatedAccount_saysSoDistinctlyFromATemporaryLock() {
        // #294 — les deux états ne doivent PLUS se ressembler : les remèdes
        // sont opposés (voir l'administration vs attendre).
        User removed = deactivatedUser();
        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(removed));

        LoginRequest req = loginReq("user@test.com", "Password1");
        assertThatThrownBy(() -> authService.login(req, "127.0.0.1"))
                .isExactlyInstanceOf(AccountLockedException.class)
                .hasMessageContaining("désactivé")
                .hasMessageContaining("administration");
    }

    @Test
    void login_lockedAccount_rejectsBeforePasswordCheck() {
        User locked = deactivatedUser();
        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(locked));

        LoginRequest req = loginReq("user@test.com", "Password1");
        assertThatThrownBy(() -> authService.login(req, "127.0.0.1"))
                .isExactlyInstanceOf(AccountLockedException.class);
    }

    @Test
    void login_success_resetCounter() {
        User user = activeUser();
        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Password1", "hashed-pw")).thenReturn(true);
        when(userRoleRepository.findByUserId(1L)).thenReturn(List.of());
        stubTokenIssuance();

        authService.login(loginReq("user@test.com", "Password1"), "127.0.0.1");

        verify(userRepository).resetFailedAttempts(1L);
        verify(userRepository, never()).incrementFailedAttempts(any());
    }

    // =========================================================================
    // refresh()
    // =========================================================================

    @Test
    void refresh_validToken_rotatesSuccessfully() {
        User user = activeUser();
        RefreshToken storedToken = RefreshToken.builder()
                .id(10L).user(user).tokenHash("old-hash").familyId("fam-1")
                .expiresAt(LocalDateTime.now().plusDays(7)).revoked(false)
                .build();

        when(jwtService.hashToken("raw-token")).thenReturn("old-hash");
        when(refreshTokenRepository.findByTokenHash("old-hash")).thenReturn(Optional.of(storedToken));
        when(userRoleRepository.findByUserId(1L)).thenReturn(List.of());
        when(jwtService.generateAccessToken(any(), any())).thenReturn("new-access-token");
        when(jwtService.generateRefreshTokenValue()).thenReturn("new-raw-refresh");
        when(jwtService.hashToken("new-raw-refresh")).thenReturn("new-hash");

        LoginResponse response = authService.refresh("raw-token");

        assertThat(response.getAccessToken()).isEqualTo("new-access-token");
        assertThat(response.getRefreshToken()).isEqualTo("new-raw-refresh");

        // Old token must be revoked in-place and saved
        assertThat(storedToken.getRevoked()).isTrue();

        // Two save() calls: first for the revoked old token, second for the new token
        ArgumentCaptor<RefreshToken> savedCaptor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository, times(2)).save(savedCaptor.capture());

        RefreshToken newToken = savedCaptor.getAllValues().get(1);
        assertThat(newToken.getFamilyId()).isEqualTo("fam-1");   // same rotation family
        assertThat(newToken.getTokenHash()).isEqualTo("new-hash");
        assertThat(newToken.getRevoked()).isFalse();
    }

    @Test
    void refresh_revokedToken_revokesFamily_throwsException() {
        User user = activeUser();
        RefreshToken revokedToken = RefreshToken.builder()
                .id(10L).user(user).tokenHash("old-hash").familyId("fam-1")
                .expiresAt(LocalDateTime.now().plusDays(7)).revoked(true)
                .build();

        when(jwtService.hashToken("raw-token")).thenReturn("old-hash");
        when(refreshTokenRepository.findByTokenHash("old-hash")).thenReturn(Optional.of(revokedToken));

        assertThatThrownBy(() -> authService.refresh("raw-token"))
                .isExactlyInstanceOf(InvalidTokenException.class)
                .hasMessageContaining("Token reuse detected");

        verify(refreshTokenRepository).revokeAllByFamilyId("fam-1");
    }

    @Test
    void refresh_expiredToken_throwsException() {
        User user = activeUser();
        RefreshToken expiredToken = RefreshToken.builder()
                .id(10L).user(user).tokenHash("old-hash").familyId("fam-1")
                .expiresAt(LocalDateTime.now().minusDays(1)).revoked(false)
                .build();

        when(jwtService.hashToken("raw-token")).thenReturn("old-hash");
        when(refreshTokenRepository.findByTokenHash("old-hash")).thenReturn(Optional.of(expiredToken));

        assertThatThrownBy(() -> authService.refresh("raw-token"))
                .isExactlyInstanceOf(InvalidTokenException.class)
                .hasMessageContaining("expired");

        // Expired token must be marked revoked and persisted before throwing
        assertThat(expiredToken.getRevoked()).isTrue();
        verify(refreshTokenRepository).save(expiredToken);
    }

    // =========================================================================
    // refresh() — #306/#217 : le refresh relit LE COMPTE, pas seulement le jeton
    // =========================================================================

    @Test
    void refresh_compteDesactive_refuse_sansConsommerLeToken() {
        // #306 — un compte retiré se réémettait un jeton neuf : le refresh ne
        // consultait jamais isActive (moitié survivante de #217).
        User user = activeUser();
        user.setIsActive(false);
        RefreshToken storedToken = RefreshToken.builder()
                .id(10L).user(user).tokenHash("old-hash").familyId("fam-1")
                .expiresAt(LocalDateTime.now().plusDays(7)).revoked(false)
                .build();

        when(jwtService.hashToken("raw-token")).thenReturn("old-hash");
        when(refreshTokenRepository.findByTokenHash("old-hash")).thenReturn(Optional.of(storedToken));

        assertThatThrownBy(() -> authService.refresh("raw-token"))
                .isExactlyInstanceOf(AccountLockedException.class)
                .hasMessageContaining("désactivé");

        // Refusé SANS consommer : le refus n'est pas une rotation.
        assertThat(storedToken.getRevoked()).isFalse();
        verify(refreshTokenRepository, never()).save(any());
        verify(jwtService, never()).generateAccessToken(any(), any());
    }

    @Test
    void refresh_compteVerrouille_refuse_avecLeDelai_sansConsommerLeToken() {
        // #294/#306 — même consigne qu'au login : « réessayez dans N minute(s) »,
        // et le refresh token SURVIT au refus — au déverrouillage la session
        // reprend sans reconnexion.
        User user = activeUser();
        user.setLockedUntil(LocalDateTime.now(clock).plusMinutes(4));
        RefreshToken storedToken = RefreshToken.builder()
                .id(10L).user(user).tokenHash("old-hash").familyId("fam-1")
                .expiresAt(LocalDateTime.now().plusDays(7)).revoked(false)
                .build();

        when(jwtService.hashToken("raw-token")).thenReturn("old-hash");
        when(refreshTokenRepository.findByTokenHash("old-hash")).thenReturn(Optional.of(storedToken));

        assertThatThrownBy(() -> authService.refresh("raw-token"))
                .isExactlyInstanceOf(AccountLockedException.class)
                .hasMessageContaining("verrouillé")
                .hasMessageContaining("minute");

        assertThat(storedToken.getRevoked()).isFalse();
        verify(refreshTokenRepository, never()).save(any());
    }

    @Test
    void refresh_verrouExpire_passe() {
        // Le verrou est TEMPORAIRE : une fois échu, le refresh reprend tout seul.
        User user = activeUser();
        user.setLockedUntil(LocalDateTime.now(clock).minusMinutes(1));
        RefreshToken storedToken = RefreshToken.builder()
                .id(10L).user(user).tokenHash("old-hash").familyId("fam-1")
                .expiresAt(LocalDateTime.now().plusDays(7)).revoked(false)
                .build();

        when(jwtService.hashToken("raw-token")).thenReturn("old-hash");
        when(refreshTokenRepository.findByTokenHash("old-hash")).thenReturn(Optional.of(storedToken));
        when(userRoleRepository.findByUserId(1L)).thenReturn(List.of());
        when(jwtService.generateAccessToken(any(), any())).thenReturn("new-access-token");
        when(jwtService.generateRefreshTokenValue()).thenReturn("new-raw-refresh");
        when(jwtService.hashToken("new-raw-refresh")).thenReturn("new-hash");

        assertThat(authService.refresh("raw-token").getAccessToken())
                .isEqualTo("new-access-token");
    }

    // =========================================================================
    // logout()
    // =========================================================================

    @Test
    void logout_revokesAllUserTokens() {
        authService.logout(1L, "user@test.com");

        verify(refreshTokenRepository).revokeAllByUserId(1L);
        verify(auditService).log(1L, "user@test.com", AuditAction.LOGOUT);
    }

    // =========================================================================
    // requestPasswordReset()
    // =========================================================================

    @Test
    void requestPasswordReset_unknownEmail_noException() {
        when(userRepository.findByEmail("ghost@test.com")).thenReturn(Optional.empty());

        // Must return normally — no user enumeration
        assertThatNoException().isThrownBy(() ->
                authService.requestPasswordReset("ghost@test.com"));

        verify(passwordResetTokenRepository, never()).save(any());
        verify(passwordResetTokenRepository, never()).invalidateAllByUserId(any());
        // No email must be dispatched for an unknown address
        verify(emailService, never()).sendPasswordResetEmail(any(), any());
    }

    @Test
    void requestPasswordReset_knownEmail_createsToken() {
        User user = activeUser();
        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));
        when(jwtService.generateRefreshTokenValue()).thenReturn("raw-reset");
        when(jwtService.hashToken("raw-reset")).thenReturn("reset-hash");

        authService.requestPasswordReset("user@test.com");

        // Old tokens for this user must be invalidated first
        verify(passwordResetTokenRepository).invalidateAllByUserId(1L);

        // New token must be saved with the correct hash and not-yet-used state
        ArgumentCaptor<PasswordResetToken> captor = ArgumentCaptor.forClass(PasswordResetToken.class);
        verify(passwordResetTokenRepository).save(captor.capture());
        PasswordResetToken saved = captor.getValue();
        assertThat(saved.getTokenHash()).isEqualTo("reset-hash");
        assertThat(saved.getUsed()).isFalse();
        assertThat(saved.getExpiresAt()).isAfter(LocalDateTime.now());
        assertThat(saved.getUser().getId()).isEqualTo(1L);
    }

    @Test
    void requestPasswordReset_knownEmail_dispatchesEmailWithRawToken() {
        User user = activeUser();
        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));
        when(jwtService.generateRefreshTokenValue()).thenReturn("raw-reset");
        when(jwtService.hashToken("raw-reset")).thenReturn("reset-hash");

        authService.requestPasswordReset("user@test.com");

        // The EmailService receives the recipient + the RAW token (only place it should travel)
        verify(emailService).sendPasswordResetEmail("user@test.com", "raw-reset");
    }

    // =========================================================================
    // confirmPasswordReset()
    // =========================================================================

    @Test
    void confirmPasswordReset_validToken_changesPassword() {
        User user = activeUser();
        PasswordResetToken resetToken = PasswordResetToken.builder()
                .id(5L).user(user).tokenHash("token-hash")
                .expiresAt(LocalDateTime.now().plusMinutes(20)).used(false)
                .build();

        PasswordResetConfirmDto dto = mock(PasswordResetConfirmDto.class);
        when(dto.getToken()).thenReturn("raw-token");
        when(dto.getNewPassword()).thenReturn("NewPassword1");

        when(jwtService.hashToken("raw-token")).thenReturn("token-hash");
        when(passwordResetTokenRepository.findByTokenHash("token-hash")).thenReturn(Optional.of(resetToken));
        when(passwordEncoder.encode("NewPassword1")).thenReturn("new-hashed-pw");
        when(userRepository.save(user)).thenReturn(user);

        authService.confirmPasswordReset(dto);

        // Password must be updated on the user entity
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getPasswordHash()).isEqualTo("new-hashed-pw");

        // Token must be marked as used
        assertThat(resetToken.getUsed()).isTrue();
        verify(passwordResetTokenRepository).save(resetToken);

        // All sessions must be revoked after a password change
        verify(refreshTokenRepository).revokeAllByUserId(1L);
    }

    @Test
    void confirmPasswordReset_usedToken_throwsException() {
        User user = activeUser();
        PasswordResetToken usedToken = PasswordResetToken.builder()
                .id(5L).user(user).tokenHash("token-hash")
                .expiresAt(LocalDateTime.now().plusMinutes(20)).used(true)
                .build();

        PasswordResetConfirmDto dto = mock(PasswordResetConfirmDto.class);
        when(dto.getToken()).thenReturn("raw-token");

        when(jwtService.hashToken("raw-token")).thenReturn("token-hash");
        when(passwordResetTokenRepository.findByTokenHash("token-hash")).thenReturn(Optional.of(usedToken));

        assertThatThrownBy(() -> authService.confirmPasswordReset(dto))
                .isExactlyInstanceOf(InvalidTokenException.class)
                .hasMessageContaining("already been used");

        verify(userRepository, never()).save(any());
    }

    @Test
    void confirmPasswordReset_expiredToken_throwsException() {
        User user = activeUser();
        PasswordResetToken expiredToken = PasswordResetToken.builder()
                .id(5L).user(user).tokenHash("token-hash")
                .expiresAt(LocalDateTime.now().minusMinutes(1)).used(false)
                .build();

        PasswordResetConfirmDto dto = mock(PasswordResetConfirmDto.class);
        when(dto.getToken()).thenReturn("raw-token");

        when(jwtService.hashToken("raw-token")).thenReturn("token-hash");
        when(passwordResetTokenRepository.findByTokenHash("token-hash")).thenReturn(Optional.of(expiredToken));

        assertThatThrownBy(() -> authService.confirmPasswordReset(dto))
                .isExactlyInstanceOf(InvalidTokenException.class)
                .hasMessageContaining("expired");

        verify(userRepository, never()).save(any());
    }

    // -------------------------------------------------------------------------
    // changePassword — utilisateur connecté, écran Profil (PR #180)
    // -------------------------------------------------------------------------
    //
    // Flux distinct de la réinitialisation par email : l'utilisateur connaît son
    // mot de passe et le change volontairement. L'identité est prouvée par le mot
    // de passe COURANT (pas par un token email), et l'userId vient du JWT — jamais
    // du corps de la requête (sinon on pourrait changer le mot de passe d'autrui).
    //
    // Ce que ces tests verrouillent :
    //   1. mot de passe actuel faux         → BadCredentialsException, RIEN n'est écrit
    //   2. utilisateur inexistant           → UsernameNotFoundException
    //   3. succès                           → nouveau hash encodé + persisté
    //   4. succès                           → TOUS les refresh tokens révoqués
    //                                         (une session volée ne survit pas au changement)
    //   5. le mot de passe n'est JAMAIS stocké en clair

    private ChangePasswordRequest changeReq(String current, String next) {
        ChangePasswordRequest req = mock(ChangePasswordRequest.class);
        lenient().when(req.getCurrentPassword()).thenReturn(current);
        lenient().when(req.getNewPassword()).thenReturn(next);
        return req;
    }

    @Test
    void changePassword_motDePasseActuelIncorrect_devraitRefuserSansRienEcrire() {
        User user = activeUser();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("mauvais", "hashed-pw")).thenReturn(false);

        assertThatThrownBy(() -> authService.changePassword(1L, changeReq("mauvais", "NewPass@99")))
                .isInstanceOf(BadCredentialsException.class);

        // Aucune écriture, aucune révocation : un échec ne doit RIEN changer.
        verify(userRepository, never()).save(any());
        verify(refreshTokenRepository, never()).revokeAllByUserId(anyLong());
        verify(passwordEncoder, never()).encode(anyString());
    }

    @Test
    void changePassword_utilisateurInexistant_devraitLever() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.changePassword(99L, changeReq("Eval@1234", "NewPass@99")))
                .isInstanceOf(UsernameNotFoundException.class);

        verify(userRepository, never()).save(any());
    }

    @Test
    void changePassword_succes_devraitEncoderEtPersisterLeNouveauHash() {
        User user = activeUser();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Eval@1234", "hashed-pw")).thenReturn(true);
        when(passwordEncoder.encode("NewPass@99")).thenReturn("nouveau-hash");

        authService.changePassword(1L, changeReq("Eval@1234", "NewPass@99"));

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getPasswordHash()).isEqualTo("nouveau-hash");
        // Le mot de passe en clair ne doit jamais atterrir en base.
        assertThat(saved.getValue().getPasswordHash()).isNotEqualTo("NewPass@99");
    }

    @Test
    void changePassword_succes_devraitRevoquerTousLesRefreshTokens() {
        User user = activeUser();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Eval@1234", "hashed-pw")).thenReturn(true);
        when(passwordEncoder.encode("NewPass@99")).thenReturn("nouveau-hash");

        authService.changePassword(1L, changeReq("Eval@1234", "NewPass@99"));

        // Le point de sécurité : après un changement de mot de passe, toutes les
        // sessions existantes meurent — y compris celle d'un attaquant qui aurait
        // volé un refresh token.
        verify(refreshTokenRepository).revokeAllByUserId(1L);
    }

    @Test
    void changePassword_succes_devraitEtreAudite() {
        User user = activeUser();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Eval@1234", "hashed-pw")).thenReturn(true);
        when(passwordEncoder.encode("NewPass@99")).thenReturn("nouveau-hash");

        authService.changePassword(1L, changeReq("Eval@1234", "NewPass@99"));

        verify(auditService).log(eq(1L), eq("user@test.com"), any(AuditAction.class), anyString(), isNull());
    }
}
