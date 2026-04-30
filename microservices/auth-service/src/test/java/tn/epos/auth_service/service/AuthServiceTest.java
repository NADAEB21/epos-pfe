package tn.epos.auth_service.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import tn.epos.auth_service.audit.AuditAction;
import tn.epos.auth_service.audit.AuditService;
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

import java.time.LocalDateTime;
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

    private User lockedUser() {
        return User.builder()
                .id(1L).email("user@test.com").passwordHash("hashed-pw")
                .nom("Test").prenom("User").isActive(false).failedLoginAttempts(3)
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

        assertThatThrownBy(() -> authService.login(loginReq("user@test.com", "wrong"), "127.0.0.1"))
                .isExactlyInstanceOf(BadCredentialsException.class);

        verify(userRepository).incrementFailedAttempts(1L);
        verify(userRepository, never()).lockAccount(any());
    }

    @Test
    void login_secondFailure_counterIs2() {
        User user = activeUser();
        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);
        when(userRepository.getFailedLoginAttempts(1L)).thenReturn(2);

        assertThatThrownBy(() -> authService.login(loginReq("user@test.com", "wrong"), "127.0.0.1"))
                .isExactlyInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("1 attempt(s) remaining");

        verify(userRepository).incrementFailedAttempts(1L);
        verify(userRepository, never()).lockAccount(any());
    }

    @Test
    void login_thirdFailure_locksAccount_and_persists() {
        User user = activeUser();
        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);
        when(userRepository.getFailedLoginAttempts(1L)).thenReturn(3);

        assertThatThrownBy(() -> authService.login(loginReq("user@test.com", "wrong"), "127.0.0.1"))
                .isExactlyInstanceOf(AccountLockedException.class);

        verify(userRepository).incrementFailedAttempts(1L);
        verify(userRepository).lockAccount(1L);

        // Verify the audit log is fired with the correct locked-account action
        verify(auditService).log(
                eq(1L), eq("user@test.com"),
                eq(AuditAction.ACCOUNT_LOCKED),
                contains("3"),
                eq("127.0.0.1"));
    }

    @Test
    void login_lockedAccount_rejectsBeforePasswordCheck() {
        User locked = lockedUser();
        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(locked));

        assertThatThrownBy(() -> authService.login(loginReq("user@test.com", "Password1"), "127.0.0.1"))
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
}
