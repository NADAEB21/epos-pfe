package tn.epos.auth_service.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tn.epos.auth_service.service.JwtService;

import java.io.IOException;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");

        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = authHeader.substring(BEARER_PREFIX.length());

        if (!jwtService.isTokenValid(token)) {
            log.debug("Invalid JWT on request to {}", request.getRequestURI());
            filterChain.doFilter(request, response);
            return;
        }

        // Only set context if not already authenticated (e.g. by a prior filter)
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            String email = jwtService.extractEmail(token);
            Long userId = jwtService.extractUserId(token);

            /*
             * Authorities are read directly from the signed JWT — the signature
             * guarantees we issued them. Per CLAUDE.md: "do not trust client claims"
             * means we reject any matiere_id that was not embedded by us at login;
             * the @PreAuthorize checks on each endpoint enforce the scoped constraint
             * using these authority strings (e.g. "ROLE_RESPONSABLE_MATIERE:5").
             */
            List<SimpleGrantedAuthority> authorities = jwtService.extractAuthorities(token)
                    .stream()
                    .map(SimpleGrantedAuthority::new)
                    .toList();

            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(email, null, authorities);

            // Attach userId as a detail so controllers can read it without re-parsing the token
            authentication.setDetails(
                    new JwtAuthenticationDetails(
                            new WebAuthenticationDetailsSource().buildDetails(request),
                            userId));

            SecurityContextHolder.getContext().setAuthentication(authentication);
        }

        filterChain.doFilter(request, response);
    }
}
