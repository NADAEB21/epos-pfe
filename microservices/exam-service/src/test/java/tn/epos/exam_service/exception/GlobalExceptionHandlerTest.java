package tn.epos.exam_service.exception;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import tn.epos.exam_service.dto.response.ApiResponse;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests for {@link GlobalExceptionHandler}. The handler has no
 * collaborators, so it is instantiated directly with no Spring context.
 */
@ExtendWith(MockitoExtension.class)
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void handleNotFound_returns404WithErrorEnvelope() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleNotFound(new ResourceNotFoundException("Examen", 99L));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isSuccess()).isFalse();
        assertThat(response.getBody().getMessage()).contains("Examen", "99");
    }

    @Test
    void handleBusiness_returns400WithErrorEnvelope() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleBusiness(new BusinessException("Statut invalide"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isSuccess()).isFalse();
        assertThat(response.getBody().getMessage()).isEqualTo("Statut invalide");
    }

    @Test
    void handleValidation_returns400WithErrorEnvelopeAndFieldErrors() {
        // Regression guard for #59: validation failures previously emitted
        // success:true with HTTP 400 — every consumer had to special-case it.
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "request");
        bindingResult.addError(new FieldError("request", "nom", "ne doit pas être vide"));
        bindingResult.addError(new FieldError("request", "duree", "doit être positif"));

        MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
        when(ex.getBindingResult()).thenReturn(bindingResult);

        ResponseEntity<ApiResponse<Map<String, String>>> response = handler.handleValidation(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isSuccess()).isFalse();
        assertThat(response.getBody().getMessage()).isEqualTo("Erreurs de validation");
        assertThat(response.getBody().getData())
                .containsEntry("nom", "ne doit pas être vide")
                .containsEntry("duree", "doit être positif");
    }

    @Test
    void handleFileTooLarge_returns400WithErrorEnvelope() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleFileTooLarge(new MaxUploadSizeExceededException(10_000_000L));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isSuccess()).isFalse();
        assertThat(response.getBody().getMessage()).contains("10 MB");
    }

    @Test
    void handleAuthentication_returns401WithErrorEnvelope() {
        // #62: previously fell through to handleGeneric() and surfaced as 500.
        AuthenticationException ex = new BadCredentialsException("internal");
        ResponseEntity<ApiResponse<Void>> response = handler.handleAuthentication(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isSuccess()).isFalse();
        assertThat(response.getBody().getMessage()).isEqualTo("Authentification requise");
    }

    @Test
    void handleAccessDenied_returns403WithErrorEnvelope() {
        // #62: previously fell through to handleGeneric() and leaked a stack trace as 500.
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleAccessDenied(new AccessDeniedException("denied"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isSuccess()).isFalse();
        assertThat(response.getBody().getMessage()).isEqualTo("Accès refusé");
    }

    @Test
    void handleConflict_returns409WithErrorEnvelope() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleConflict(new ConflictException("Examen déjà publié"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isSuccess()).isFalse();
        assertThat(response.getBody().getMessage()).isEqualTo("Examen déjà publié");
    }

    @Test
    void handleGeneric_returns500WithErrorEnvelope() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleGeneric(new RuntimeException("boom"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isSuccess()).isFalse();
        assertThat(response.getBody().getMessage()).contains("boom");
    }
}
