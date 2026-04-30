package tn.epos.auth_service.config;

import org.springframework.security.web.authentication.WebAuthenticationDetails;

/**
 * Extends WebAuthenticationDetails to carry the resolved userId from the JWT,
 * so controllers and services can call SecurityUtils.getCurrentUserId()
 * without re-parsing the token.
 */
public class JwtAuthenticationDetails extends WebAuthenticationDetails {

    private final Long userId;

    public JwtAuthenticationDetails(WebAuthenticationDetails delegate, Long userId) {
        super(delegate.getRemoteAddress(), delegate.getSessionId());
        this.userId = userId;
    }

    public Long getUserId() {
        return userId;
    }
}
