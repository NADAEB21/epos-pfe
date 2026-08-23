package tn.epos.scoring_service.exception;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import tn.epos.common.dto.ApiResponse;
import tn.epos.common.exception.BusinessException;
import tn.epos.common.exception.ConflictException;
import tn.epos.common.exception.ResourceNotFoundException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests for {@link GlobalExceptionHandler}. The handler has no
 * collaborators, so it is instantiated directly with no Spring context.
 * Mirror of the exam-service test for issue #63.
 */
@ExtendWith(MockitoExtension.class)
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void handleNotFound_returns404WithErrorEnvelope() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleNotFound(new ResourceNotFoundException("Lot", 99L));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isSuccess()).isFalse();
        assertThat(response.getBody().getMessage()).contains("Lot", "99");
    }

    @Test
    void handleBusiness_returns400WithErrorEnvelope() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleBusiness(new BusinessException("Impossible de modifier une notation verrouillée."));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isSuccess()).isFalse();
        assertThat(response.getBody().getMessage()).contains("verrouillée");
    }

    @Test
    void handleDataIntegrity_returns409WithErrorEnvelope() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleDataIntegrity(new DataIntegrityViolationException(
                        "uq_participation_examen_etudiant violated"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isSuccess()).isFalse();
        assertThat(response.getBody().getMessage()).contains("Conflit");
    }

    @Test
    void handleConflict_returns409WithSpecificMessage() {
        String errorMessage = "Le numéro d'inscription « 481 » est déjà utilisé par Yassine Khelifi (id 2).";

        ResponseEntity<ApiResponse<Void>> response =
                handler.handleConflict(new ConflictException(errorMessage));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isSuccess()).isFalse();
        // Contrairement à handleDataIntegrity, ici on vérifie que le message EXACT est transmis
        assertThat(response.getBody().getMessage()).isEqualTo(errorMessage);
    }

    @Test
    void handleValidation_returns400WithErrorEnvelopeAndFieldErrors() {
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "request");
        bindingResult.addError(new FieldError("request", "score", "doit être positif"));
        bindingResult.addError(new FieldError("request", "evaluateurId", "ne doit pas être null"));

        MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
        when(ex.getBindingResult()).thenReturn(bindingResult);

        ResponseEntity<ApiResponse<Map<String, String>>> response = handler.handleValidation(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isSuccess()).isFalse();
        assertThat(response.getBody().getMessage()).isEqualTo("Validation failed");
        assertThat(response.getBody().getData())
                .containsEntry("score", "doit être positif")
                .containsEntry("evaluateurId", "ne doit pas être null");
    }

    @Test
    void handleAuthentication_returns401WithErrorEnvelope() {
        AuthenticationException ex = new BadCredentialsException("internal");

        ResponseEntity<ApiResponse<Void>> response = handler.handleAuthentication(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isSuccess()).isFalse();
        assertThat(response.getBody().getMessage()).isEqualTo("Authentication required");
    }

    @Test
    void handleAccessDenied_returns403WithErrorEnvelope() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleAccessDenied(new AccessDeniedException("denied"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isSuccess()).isFalse();
        assertThat(response.getBody().getMessage()).isEqualTo("Access denied");
    }

    @Test
    void handleUnexpected_returns500WithErrorEnvelope() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleUnexpected(new RuntimeException("boom"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isSuccess()).isFalse();
        assertThat(response.getBody().getMessage()).isEqualTo("An unexpected error occurred");
    }
}
