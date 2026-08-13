package tn.epos.auth_service.config;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tn.epos.common.security.revocation.InternalCallAuthenticator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * #306 — la porte {@code /internal/**} : preuve HMAC dérivée de JWT_SECRET, sinon 401 sec.
 * Testé SANS contexte Spring : le filtre décide seul, c'est tout l'intérêt.
 */
class InternalAuthFilterTest {

    private static final String SECRET = "un-secret-de-test-suffisamment-long-32o";

    private final InternalAuthFilter filter = new InternalAuthFilter(SECRET);

    private MockHttpServletRequest requeteInterne() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/internal/revocations");
        request.setRequestURI("/internal/revocations");
        return request;
    }

    @Test
    @DisplayName("preuve correcte → la chaîne continue")
    void preuveCorrectePasse() throws Exception {
        MockHttpServletRequest request = requeteInterne();
        request.addHeader(InternalCallAuthenticator.HEADER, InternalCallAuthenticator.headerValue(SECRET));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    @DisplayName("sans en-tête → 401, la chaîne ne continue pas")
    void sansEnTete401() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(requeteInterne(), response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("preuve dérivée d'un AUTRE secret → 401 — deux déploiements ne se parlent pas")
    void mauvaisSecret401() throws Exception {
        MockHttpServletRequest request = requeteInterne();
        request.addHeader(InternalCallAuthenticator.HEADER,
                InternalCallAuthenticator.headerValue("un-autre-secret-tout-aussi-long-32o!"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("hors /internal → le filtre ne s'applique pas (shouldNotFilter)")
    void horsInternalIgnore() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/users");
        request.setRequestURI("/api/v1/users");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }
}
