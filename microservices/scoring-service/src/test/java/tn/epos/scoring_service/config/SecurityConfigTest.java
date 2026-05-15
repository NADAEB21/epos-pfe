package tn.epos.scoring_service.config;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fail-fast unit tests for SecurityConfig's JWT_SECRET validation (issue #47).
 * Mirrors auth-service's JwtService test pattern from #6.
 */
class SecurityConfigTest {

    @Test
    void validateJwtSecret_nullSecret_throwsIllegalStateException() {
        SecurityConfig config = new SecurityConfig();
        ReflectionTestUtils.setField(config, "jwtSecret", null);

        assertThatThrownBy(config::validateJwtSecret)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    void validateJwtSecret_blankSecret_throwsIllegalStateException() {
        SecurityConfig config = new SecurityConfig();
        ReflectionTestUtils.setField(config, "jwtSecret", "   ");

        assertThatThrownBy(config::validateJwtSecret)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    void validateJwtSecret_shortSecret_throwsIllegalStateException() {
        SecurityConfig config = new SecurityConfig();
        ReflectionTestUtils.setField(config, "jwtSecret", "too-short");

        assertThatThrownBy(config::validateJwtSecret)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 bytes");
    }

    @Test
    void validateJwtSecret_validSecret_passes() {
        SecurityConfig config = new SecurityConfig();
        ReflectionTestUtils.setField(config, "jwtSecret",
                "a-secret-that-is-definitely-longer-than-32-bytes");

        assertThatCode(config::validateJwtSecret).doesNotThrowAnyException();
    }
}

