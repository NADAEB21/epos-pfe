package tn.epos.scoring_service.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import tn.epos.common.dto.ApiResponse;
import tn.epos.common.exception.BusinessException;
import tn.epos.common.exception.ResourceNotFoundException;
import tn.epos.common.exception.ConflictException;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * Centralised translation of exceptions to HTTP responses. Handlers mirror the
 * exam-service shape so the wire contract is uniform across services
 * (issue #63 — adds 404 / 401, plus typed {@link ResourceNotFoundException}
 * and {@link BusinessException} that replace the raw {@code RuntimeException}
 * throws previously caught per-controller).
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // 404 - Ressource introuvable
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(ResourceNotFoundException ex) {
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(ex.getMessage()));
    }

    // 400 - Règle métier violée (e.g. modifying a locked notation)
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException ex) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(ex.getMessage()));
    }

    // 409 - Violation de contrainte d'unicité (e.g. même étudiant inscrit deux
    // fois au même examen → contrainte uq_participation_examen_etudiant).
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataIntegrity(DataIntegrityViolationException ex) {
        log.warn("Violation d'intégrité des données : {}", ex.getMostSpecificCause().getMessage());
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(ApiResponse.error("Conflit : cette ressource existe déjà ou viole une contrainte d'unicité."));
    }

    // 400 - Validation @Valid des DTOs
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleValidation(
            MethodArgumentNotValidException ex) {
        Map<String, String> errors = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .collect(Collectors.toMap(
                        FieldError::getField,
                        e -> e.getDefaultMessage() != null ? e.getDefaultMessage() : "Invalid value",
                        (a, b) -> a));
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error("Validation failed", errors));
    }

    // 401 - Authentification requise. With OAuth2 resource server, most 401s
    // are produced by BearerTokenAuthenticationEntryPoint before reaching here;
    // this handler covers cases where an AuthenticationException propagates out
    // of a controller method (parity with exam-service GEH).
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuthentication(AuthenticationException ex) {
        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error("Authentication required"));
    }

    // 403 - Accès refusé
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error("Access denied"));
    }

    // 404 - URL inconnue (parity with exam-service GEH). Without this,
    // NoResourceFoundException reaches the catch-all below and an unknown path
    // returns 500 instead of 404.
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResource(NoResourceFoundException ex) {
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error("No such endpoint: " + ex.getResourcePath()));
    }

    /**
     * 405 — méthode non autorisée sur un chemin qui existe (#286, instance scoring).
     *
     * <p>Sans ce mappage, {@code HttpRequestMethodNotSupportedException} tombait dans le
     * fourre-tout ci-dessous : appeler une méthode inexistante sur un chemin connu répondait
     * <b>500</b>, donc « le serveur est cassé » au lieu de « cette méthode n'existe pas ».
     *
     * <p>Trouvé en supprimant les écritures brutes de {@code /api/rotations} et
     * {@code /api/assignments} (#86, #218) : les tests qui vérifient que ces routes n'existent
     * plus recevaient 500. Le voisin {@code NoResourceFoundException} → 404 était déjà là ;
     * c'est la même famille et il manquait la moitié « méthode ».
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException ex) {
        return ResponseEntity
                .status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ApiResponse.error("Méthode " + ex.getMethod() + " non autorisée sur ce chemin"));
    }

    // 409 - Doublon détecté explicitement AVANT écriture (#351, ex: numero_inscription
    // déjà pris). Message NOMINATIF, contrairement au repli générique
    // DataIntegrityViolationException ci-dessus, qui reste le filet pour la course
    // concurrente échappant au pré-check (deux requêtes simultanées).
    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiResponse<Void>> handleConflict(ConflictException ex) {
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(ex.getMessage()));
    }

    // 500 - Erreur inattendue (last-resort fallback)
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("An unexpected error occurred"));
    }
}
