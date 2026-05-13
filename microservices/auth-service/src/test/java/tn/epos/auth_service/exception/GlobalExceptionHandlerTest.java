package tn.epos.auth_service.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import tn.epos.auth_service.dto.ApiResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for {@link GlobalExceptionHandler}. The handler has no
 * collaborators, so it is instantiated directly with no Spring context.
 *
 * Focus: every 401-yielding exception must surface the identical generic
 * message so the response body does not reveal which field was wrong or
 * whether the email exists (issue #8).
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void handleBadCredentials_returnsGenericMessage() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleBadCredentials(new BadCredentialsException("internal detail"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).isEqualTo("Invalid email or password");
        assertThat(response.getBody().isSuccess()).isFalse();
    }

    @Test
    void handleUsernameNotFound_returnsSameGenericMessage() {
        // Wrong-email path must be indistinguishable from wrong-password path
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleBadCredentials(new UsernameNotFoundException("user does not exist"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).isEqualTo("Invalid email or password");
        assertThat(response.getBody().isSuccess()).isFalse();
    }
}
