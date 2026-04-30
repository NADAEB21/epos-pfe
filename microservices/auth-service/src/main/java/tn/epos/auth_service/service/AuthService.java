package tn.epos.auth_service.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.epos.auth_service.audit.AuditAction;
import tn.epos.auth_service.audit.AuditService;
import tn.epos.auth_service.dto.LoginRequest;
import tn.epos.auth_service.dto.LoginResponse;
import tn.epos.auth_service.dto.PasswordResetConfirmDto;
import tn.epos.auth_service.entity.PasswordResetToken;
import tn.epos.auth_service.entity.RefreshToken;
import tn.epos.auth_service.entity.User;
import tn.epos.auth_service.entity.UserRole;
import tn.epos.auth_service.exception.AccountLockedException;
import tn.epos.auth_service.exception.InvalidTokenException;
import tn.epos.auth_service.repository.PasswordResetTokenRepository;
import tn.epos.auth_service.repository.RefreshTokenRepository;
import tn.epos.auth_service.repository.UserRepository;
import tn.epos.auth_service.repository.UserRoleRepository;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final int MAX_FAILED_ATTEMPTS = 3;
    private static final long REFRESH_TOKEN_VALIDITY_DAYS = 7;
    private static final long RESET_TOKEN_VALIDITY_MINUTES = 30;

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    // -------------------------------------------------------------------------
    // Login
    // -------------------------------------------------------------------------

    @Transactional
    public LoginResponse login(LoginRequest request, String ipAddress) {
        log.debug("Login attempt for: {}", request.getEmail());

        // a. Find user — unknown email gets the same generic error as wrong password
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));

        log.debug("User loaded: {}, isActive: {}", user.getEmail(), user.getIsActive());

        // b. Reject locked accounts before touching the password
        if (!user.getIsActive()) {
            auditService.log(user.getId(), user.getEmail(),
                    AuditAction.LOGIN_FAILURE, "Account locked", ipAddress);
            throw new AccountLockedException("Account is locked");
        }

        // c. Manual password check — keeps the failure path inside our control
        log.debug("Password match result: {}", passwordEncoder.matches(request.getPassword(), user.getPasswordHash()));
        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            // d. Increment directly in DB to avoid lost-update races, then re-read
            userRepository.incrementFailedAttempts(user.getId());
            int attempts = userRepository.getFailedLoginAttempts(user.getId());

            if (attempts >= MAX_FAILED_ATTEMPTS) {
                userRepository.lockAccount(user.getId());
                auditService.log(user.getId(), user.getEmail(),
                        AuditAction.ACCOUNT_LOCKED,
                        "Locked after " + attempts + " failed attempts", ipAddress);
                throw new AccountLockedException("Account locked after too many failed attempts");
            }

            auditService.log(user.getId(), user.getEmail(),
                    AuditAction.LOGIN_FAILURE,
                    "Failed attempt " + attempts + "/" + MAX_FAILED_ATTEMPTS, ipAddress);
            throw new BadCredentialsException(
                    "Invalid credentials (" + (MAX_FAILED_ATTEMPTS - attempts) + " attempt(s) remaining)");
        }

        // e. Correct password — reset counter, issue tokens
        userRepository.resetFailedAttempts(user.getId());

        List<UserRole> roles = userRoleRepository.findByUserId(user.getId());
        LoginResponse response = issueTokenPair(user, roles, null);

        auditService.log(user.getId(), user.getEmail(),
                AuditAction.LOGIN_SUCCESS, null, ipAddress);

        return response;
    }

    // -------------------------------------------------------------------------
    // Refresh
    // -------------------------------------------------------------------------

    @Transactional
    public LoginResponse refresh(String rawRefreshToken) {
        String hash = jwtService.hashToken(rawRefreshToken);

        RefreshToken stored = refreshTokenRepository.findByTokenHash(hash)
                .orElseThrow(() -> new InvalidTokenException("Invalid refresh token"));

        if (stored.getRevoked()) {
            // Reuse of a revoked token → security breach assumed: nuke entire family
            refreshTokenRepository.revokeAllByFamilyId(stored.getFamilyId());
            auditService.log(
                    stored.getUser().getId(), stored.getUser().getEmail(),
                    AuditAction.TOKEN_REUSE_DETECTED,
                    "Family " + stored.getFamilyId() + " fully revoked", null);
            throw new InvalidTokenException("Token reuse detected — all sessions have been revoked");
        }

        if (stored.getExpiresAt().isBefore(LocalDateTime.now())) {
            stored.setRevoked(true);
            refreshTokenRepository.save(stored);
            throw new InvalidTokenException("Refresh token has expired");
        }

        // Rotate: revoke the used token, issue a new one in the same family
        stored.setRevoked(true);
        refreshTokenRepository.save(stored);

        User user = stored.getUser();
        List<UserRole> roles = userRoleRepository.findByUserId(user.getId());
        LoginResponse response = issueTokenPair(user, roles, stored.getFamilyId());

        auditService.log(user.getId(), user.getEmail(), AuditAction.TOKEN_REFRESHED);

        return response;
    }

    // -------------------------------------------------------------------------
    // Logout
    // -------------------------------------------------------------------------

    @Transactional
    public void logout(Long userId, String email) {
        refreshTokenRepository.revokeAllByUserId(userId);
        auditService.log(userId, email, AuditAction.LOGOUT);
    }

    // -------------------------------------------------------------------------
    // Password reset — request
    // -------------------------------------------------------------------------

    @Transactional
    public void requestPasswordReset(String email) {
        /*
         * Always return normally regardless of whether the email exists.
         * This prevents user-enumeration attacks on the reset endpoint.
         */
        userRepository.findByEmail(email).ifPresent(user -> {
            // Invalidate any existing unused tokens for this user
            passwordResetTokenRepository.invalidateAllByUserId(user.getId());

            String rawToken = jwtService.generateRefreshTokenValue();
            PasswordResetToken resetToken = PasswordResetToken.builder()
                    .user(user)
                    .tokenHash(jwtService.hashToken(rawToken))
                    .expiresAt(LocalDateTime.now().plusMinutes(RESET_TOKEN_VALIDITY_MINUTES))
                    .used(false)
                    .build();
            passwordResetTokenRepository.save(resetToken);

            // TODO: dispatch via email-service; for now log at DEBUG only
            log.debug("Password reset token for {}: {}", email, rawToken);

            auditService.log(user.getId(), user.getEmail(),
                    AuditAction.PASSWORD_RESET_REQUESTED, null, null);
        });
    }

    // -------------------------------------------------------------------------
    // Password reset — confirm
    // -------------------------------------------------------------------------

    @Transactional
    public void confirmPasswordReset(PasswordResetConfirmDto dto) {
        String hash = jwtService.hashToken(dto.getToken());

        PasswordResetToken resetToken = passwordResetTokenRepository.findByTokenHash(hash)
                .orElseThrow(() -> new InvalidTokenException("Invalid reset token"));

        if (resetToken.getUsed()) {
            throw new InvalidTokenException("Reset token has already been used");
        }
        if (resetToken.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new InvalidTokenException("Reset token has expired");
        }

        User user = resetToken.getUser();
        user.setPasswordHash(passwordEncoder.encode(dto.getNewPassword()));
        userRepository.save(user);

        resetToken.setUsed(true);
        passwordResetTokenRepository.save(resetToken);

        // Force re-login on all devices after a password change
        refreshTokenRepository.revokeAllByUserId(user.getId());

        auditService.log(user.getId(), user.getEmail(),
                AuditAction.PASSWORD_RESET_CONFIRMED, null, null);
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Persists a fresh access + refresh token pair.
     *
     * @param existingFamilyId pass the current family ID on rotation, null on first login
     */
    private LoginResponse issueTokenPair(User user, List<UserRole> roles, String existingFamilyId) {
        String accessToken = jwtService.generateAccessToken(user, roles);
        String rawRefresh = jwtService.generateRefreshTokenValue();
        String familyId = existingFamilyId != null
                ? existingFamilyId
                : jwtService.generateFamilyId();

        RefreshToken refreshToken = RefreshToken.builder()
                .user(user)
                .tokenHash(jwtService.hashToken(rawRefresh))
                .expiresAt(LocalDateTime.now().plusDays(REFRESH_TOKEN_VALIDITY_DAYS))
                .revoked(false)
                .familyId(familyId)
                .build();
        refreshTokenRepository.save(refreshToken);

        return LoginResponse.builder()
                .accessToken(accessToken)
                .refreshToken(rawRefresh)
                .build();
    }

}
