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
import tn.epos.auth_service.dto.ChangePasswordRequest;
import tn.epos.auth_service.dto.LoginRequest;
import tn.epos.auth_service.dto.LoginResponse;
import tn.epos.auth_service.dto.PasswordResetConfirmDto;
import tn.epos.auth_service.entity.PasswordResetToken;
import tn.epos.auth_service.entity.RefreshToken;
import tn.epos.auth_service.entity.User;
import tn.epos.auth_service.entity.UserRole;
import tn.epos.auth_service.exception.AccountLockedException;
import tn.epos.auth_service.exception.GlobalExceptionHandler;
import tn.epos.auth_service.exception.InvalidTokenException;
import tn.epos.auth_service.repository.PasswordResetTokenRepository;
import tn.epos.auth_service.repository.RefreshTokenRepository;
import tn.epos.auth_service.repository.UserRepository;
import tn.epos.auth_service.repository.UserRoleRepository;
import tn.epos.auth_service.service.email.EmailService;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final int MAX_FAILED_ATTEMPTS = 3;

    /**
     * #294 — barème du verrou temporaire. Premier verrou 2 min, puis doublement
     * (2 · 4 · 8 · 16 · 30…), plafonné. Choisi pour le contexte réel : le matin
     * d'une épreuve, deux minutes se survivent ; un verrou définitif, non.
     */
    private static final long LOCK_BASE_MINUTES = 2;
    private static final long LOCK_MAX_MINUTES = 30;
    private static final long REFRESH_TOKEN_VALIDITY_DAYS = 7;
    private static final long RESET_TOKEN_VALIDITY_MINUTES = 30;

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final JwtService jwtService;
    private final Clock clock;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final EmailService emailService;
    private final TokenRevocationService tokenRevocationService;

    // -------------------------------------------------------------------------
    // Login
    // -------------------------------------------------------------------------

    /** #294 — durée du n-ième verrou consécutif : 2·2^(n-1), plafonnée. */
    private long lockDurationMinutes(int lockNumber) {
        long minutes = LOCK_BASE_MINUTES << Math.min(lockNumber - 1, 10);
        return Math.min(minutes, LOCK_MAX_MINUTES);
    }

    @Transactional
    public LoginResponse login(LoginRequest request, String ipAddress) {
        log.debug("Login attempt for: {}", request.getEmail());

        // a. Find user — unknown email gets the same generic error as wrong password
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new UsernameNotFoundException(GlobalExceptionHandler.INVALID_CREDENTIALS_MESSAGE));

        log.debug("User loaded: {}, isActive: {}", user.getEmail(), user.getIsActive());

        // b1. Compte retiré par l'administration — état DURABLE, sortie par
        // l'administration seule. Message distinct de b2 : #294 a montré que
        // confondre les deux laisse l'utilisateur sans la moindre consigne.
        // Boolean.TRUE.equals() guards against the wrapper being null (S5411).
        if (!Boolean.TRUE.equals(user.getIsActive())) {
            auditService.log(user.getId(), user.getEmail(),
                    AuditAction.LOGIN_FAILURE, "Account deactivated", ipAddress);
            throw new AccountLockedException(
                    "Compte désactivé. Contactez l'administration de la faculté.");
        }

        // b2. Verrou TEMPORAIRE anti-force-brute (#294) — expire tout seul.
        // On annonce le délai restant : sans lui, l'utilisateur ne peut pas
        // distinguer « attends deux minutes » de « ton compte est mort ».
        LocalDateTime now = LocalDateTime.now(clock);
        if (user.getLockedUntil() != null && now.isBefore(user.getLockedUntil())) {
            // ⚠️ Duration.between(LocalDateTime, LocalDateTime) est AVEUGLE au
            // changement d'heure (S6883, déjà corrigé une fois sur le Suivi) :
            // un verrou posé la nuit du passage à l'heure d'hiver annoncerait
            // une heure de trop. On ancre les deux bornes sur la zone de
            // l'horloge avant de mesurer.
            long minutes = Math.max(1, Duration.between(
                    now.atZone(clock.getZone()).toInstant(),
                    user.getLockedUntil().atZone(clock.getZone()).toInstant()).toMinutes() + 1);
            auditService.log(user.getId(), user.getEmail(),
                    AuditAction.LOGIN_FAILURE, "Temporarily locked", ipAddress);
            throw new AccountLockedException(
                    "Compte temporairement verrouillé après plusieurs tentatives. "
                            + "Réessayez dans " + minutes + " minute(s).");
        }

        // c. Manual password check — keeps the failure path inside our control
        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            // d. Increment directly in DB to avoid lost-update races, then re-read
            userRepository.incrementFailedAttempts(user.getId());
            int attempts = userRepository.getFailedLoginAttempts(user.getId());

            if (attempts >= MAX_FAILED_ATTEMPTS) {
                // #294 — verrou TEMPORAIRE à backoff exponentiel, jamais définitif.
                // Un verrou définitif faisait de 3 requêtes anonymes une exclusion
                // irréversible ; le backoff rend l'attaque coûteuse à MAINTENIR
                // sans jamais fabriquer un utilisateur bloqué à vie.
                int nextLock = userRepository.getLockCount(user.getId()) + 1;
                long minutes = lockDurationMinutes(nextLock);
                LocalDateTime until = LocalDateTime.now(clock).plusMinutes(minutes);
                userRepository.applyTemporaryLock(user.getId(), until);
                auditService.log(user.getId(), user.getEmail(),
                        AuditAction.ACCOUNT_LOCKED,
                        "Locked " + minutes + " min after " + attempts
                                + " failed attempts (lock #" + nextLock + ")", ipAddress);
                throw new AccountLockedException(
                        "Compte temporairement verrouillé après " + attempts
                                + " tentatives. Réessayez dans " + minutes + " minute(s).");
            }

            // Counter detail stays on the server, in the audit log; never in the response.
            auditService.log(user.getId(), user.getEmail(),
                    AuditAction.LOGIN_FAILURE,
                    "Failed attempt " + attempts + "/" + MAX_FAILED_ATTEMPTS, ipAddress);
            throw new BadCredentialsException(GlobalExceptionHandler.INVALID_CREDENTIALS_MESSAGE);
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

        if (Boolean.TRUE.equals(stored.getRevoked())) {
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

        User user = stored.getUser();

        // #306/#217 — le refresh relisait le jeton, jamais LE COMPTE : un compte retiré ou
        // verrouillé se réémettait un jeton d'accès neuf. Mêmes états et mêmes messages que
        // login (b1/b2) — l'appelant est le même humain, la consigne doit être la même.
        // Le refresh token présenté est déjà consommé plus haut si réutilisé ; ici on refuse
        // SANS le consommer : au déverrouillage, la session reprend sans reconnexion.
        if (!Boolean.TRUE.equals(user.getIsActive())) {
            auditService.log(user.getId(), user.getEmail(),
                    AuditAction.LOGIN_FAILURE, "Refresh refusé : compte désactivé", null);
            throw new AccountLockedException(
                    "Compte désactivé. Contactez l'administration de la faculté.");
        }
        LocalDateTime maintenant = LocalDateTime.now(clock);
        if (user.getLockedUntil() != null && maintenant.isBefore(user.getLockedUntil())) {
            long minutes = Math.max(1, Duration.between(
                    maintenant.atZone(clock.getZone()).toInstant(),
                    user.getLockedUntil().atZone(clock.getZone()).toInstant()).toMinutes() + 1);
            auditService.log(user.getId(), user.getEmail(),
                    AuditAction.LOGIN_FAILURE, "Refresh refusé : compte verrouillé", null);
            throw new AccountLockedException(
                    "Compte temporairement verrouillé après plusieurs tentatives. "
                            + "Réessayez dans " + minutes + " minute(s).");
        }

        // Rotate: revoke the used token, issue a new one in the same family
        stored.setRevoked(true);
        refreshTokenRepository.save(stored);

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
    // Changement de mot de passe (utilisateur déjà authentifié — écran Profil)
    // -------------------------------------------------------------------------

    /**
     * Change le mot de passe d'un utilisateur connecté après vérification de
     * son mot de passe actuel. Contrairement à {@link #confirmPasswordReset},
     * ce flux ne passe par aucun token email — l'utilisateur prouve son
     * identité en fournissant son mot de passe courant.
     *
     * Révoque tous les refresh tokens actifs : après un changement de mot de
     * passe, toutes les sessions existantes doivent se reconnecter avec la
     * nouvelle valeur.
     *
     * @throws UsernameNotFoundException si l'utilisateur n'existe plus
     * @throws BadCredentialsException   si le mot de passe actuel est incorrect
     */
    @Transactional
    public void changePassword(Long userId, ChangePasswordRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UsernameNotFoundException("Utilisateur introuvable"));

        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPasswordHash())) {
            auditService.log(user.getId(), user.getEmail(),
                    AuditAction.LOGIN_FAILURE, "Échec changement de mot de passe : mdp actuel incorrect", null);
            throw new BadCredentialsException("Mot de passe actuel incorrect");
        }

        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        // #306 — changer son mot de passe, c'est (aussi) chasser un éventuel porteur du
        // jeton : les jetons d'accès émis avant l'acte meurent, pas seulement les refresh.
        // Report sur l'entité obligatoire (contrat de revokeIssuedTokens).
        user.setTokensInvalidBefore(
                tokenRevocationService.revokeIssuedTokens(userId, "changement de mot de passe"));
        userRepository.save(user);

        // Force la reconnexion sur tous les appareils avec le nouveau mot de passe.
        refreshTokenRepository.revokeAllByUserId(userId);

        auditService.log(user.getId(), user.getEmail(),
                AuditAction.PASSWORD_RESET_CONFIRMED, "Changement de mot de passe depuis le profil", null);
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

            // Le token est déjà persisté à ce stade : un incident SMTP (réseau de
            // la faculté qui bloque le port, credentials invalides, timeout…) ne
            // doit ni faire échouer la requête (500 côté app pour l'utilisateur),
            // ni annuler la création du token (elle est utile pour le diagnostic
            // même si l'email n'est pas parti). On journalise l'échec au lieu de
            // laisser l'exception remonter et faire rollback la transaction.
            try {
                emailService.sendPasswordResetEmail(user.getEmail(), rawToken);
            } catch (Exception e) {
                log.error("Échec de l'envoi de l'email de réinitialisation à {} : {}",
                        user.getEmail(), e.getMessage(), e);
            }

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

        if (Boolean.TRUE.equals(resetToken.getUsed())) {
            throw new InvalidTokenException("Reset token has already been used");
        }
        if (resetToken.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new InvalidTokenException("Reset token has expired");
        }

        User user = resetToken.getUser();
        user.setPasswordHash(passwordEncoder.encode(dto.getNewPassword()));
        // #294 — une réinitialisation réussie LÈVE le verrou temporaire. Prouver
        // la maîtrise de sa boîte mail est un signal plus fort que trois saisies
        // ratées ; sans cela, le produit répondait « Password updated
        // successfully » à quelqu'un qui restait dehors.
        // ⚠️ N'affecte PAS isActive : un retrait administratif se lève par
        // l'administration seule (ADR-0023 D1, réactivation = #289).
        user.setLockedUntil(null);
        user.setLockCount(0);
        user.setFailedLoginAttempts(0);
        // #306 — même geste que changePassword : le scénario nominal d'une réinitialisation
        // est précisément « quelqu'un d'autre tient peut-être ma session ».
        user.setTokensInvalidBefore(tokenRevocationService.revokeIssuedTokens(
                user.getId(), "réinitialisation de mot de passe"));
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