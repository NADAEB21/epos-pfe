package tn.epos.auth_service.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tn.epos.common.security.revocation.InternalCallAuthenticator;

import java.io.IOException;

/**
 * #306 — garde des endpoints {@code /internal/**} : réservés aux pairs qui détiennent
 * {@code JWT_SECRET} (ils présentent la preuve HMAC dérivée, jamais le secret lui-même).
 *
 * <p>Ces endpoints sont {@code permitAll} dans {@code SecurityConfig} — c'est CE filtre qui
 * décide, avant la chaîne Spring Security. Un refus est un 401 sec, sans détail : le seul
 * appelant légitime est un service du compose, pas un humain à guider.
 */
@Component
public class InternalAuthFilter extends OncePerRequestFilter {

    private final String jwtSecret;

    public InternalAuthFilter(@Value("${jwt.secret}") String jwtSecret) {
        this.jwtSecret = jwtSecret;
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/internal/");
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        String presented = request.getHeader(InternalCallAuthenticator.HEADER);
        if (!InternalCallAuthenticator.matches(jwtSecret, presented)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"success\":false,\"message\":\"Unauthorized\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }
}
