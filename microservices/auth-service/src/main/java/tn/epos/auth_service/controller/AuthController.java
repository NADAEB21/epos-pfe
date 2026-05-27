package tn.epos.auth_service.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tn.epos.auth_service.config.JwtAuthenticationDetails;
import tn.epos.common.dto.ApiResponse;
import tn.epos.auth_service.dto.LoginRequest;
import tn.epos.auth_service.dto.LoginResponse;
import tn.epos.auth_service.dto.PasswordResetConfirmDto;
import tn.epos.auth_service.dto.PasswordResetRequestDto;
import tn.epos.auth_service.dto.RefreshRequest;
import tn.epos.auth_service.service.AuthService;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * POST /api/v1/auth/login
     * Public — no token required.
     */
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest) {
        LoginResponse response = authService.login(request, httpRequest.getRemoteAddr());
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    /**
     * POST /api/v1/auth/refresh
     * Public — accepts an opaque refresh token, returns a rotated token pair.
     */
    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<LoginResponse>> refresh(
            @Valid @RequestBody RefreshRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(authService.refresh(request.getRefreshToken())));
    }

    /**
     * POST /api/v1/auth/logout
     * Requires a valid JWT. Revokes all active refresh tokens for the caller.
     */
    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(Authentication authentication) {
        Long userId = resolveUserId(authentication);
        authService.logout(userId, authentication.getName());
        return ResponseEntity.ok(ApiResponse.ok("Logged out successfully"));
    }

    /**
     * POST /api/v1/auth/password-reset/request
     * Public — always returns 200 to prevent user enumeration.
     */
    @PostMapping("/password-reset/request")
    public ResponseEntity<ApiResponse<Void>> requestPasswordReset(
            @Valid @RequestBody PasswordResetRequestDto dto) {
        authService.requestPasswordReset(dto.getEmail());
        return ResponseEntity.ok(ApiResponse.ok(
                "If that address is registered, a reset link has been sent"));
    }

    /**
     * POST /api/v1/auth/password-reset/confirm
     * Public — consumes the one-time token and sets the new password.
     */
    @PostMapping("/password-reset/confirm")
    public ResponseEntity<ApiResponse<Void>> confirmPasswordReset(
            @Valid @RequestBody PasswordResetConfirmDto dto) {
        authService.confirmPasswordReset(dto);
        return ResponseEntity.ok(ApiResponse.ok("Password updated successfully"));
    }

    // -------------------------------------------------------------------------

    private Long resolveUserId(Authentication authentication) {
        if (authentication.getDetails() instanceof JwtAuthenticationDetails details) {
            return details.getUserId();
        }
        throw new IllegalStateException("JwtAuthenticationDetails missing from SecurityContext");
    }
}
