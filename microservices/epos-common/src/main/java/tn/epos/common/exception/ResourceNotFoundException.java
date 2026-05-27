package tn.epos.common.exception;

/**
 * Thrown by services when a lookup by id (or other identifier) finds nothing.
 * Mapped to HTTP 404 by each service's {@code GlobalExceptionHandler}.
 *
 * <p>Extracted into {@code epos-common} as part of #68. Previously
 * duplicated in exam-service and scoring-service (#63/#77). The two-arg
 * constructor uses the exam-service wording ("introuvable"); scoring-service
 * code that built its own message via the single-arg constructor is unaffected.
 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String resource, Long id) {
        super(resource + " introuvable avec l'id : " + id);
    }

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
