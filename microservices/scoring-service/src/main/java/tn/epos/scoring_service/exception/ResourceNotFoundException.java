package tn.epos.scoring_service.exception;

/**
 * Thrown by services when a lookup by id (or other identifier) finds nothing.
 * Mapped to HTTP 404 by {@link GlobalExceptionHandler}. Mirror of the
 * exam-service exception class. See issue #63.
 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String resource, Long id) {
        super(resource + " non trouvé avec l'id : " + id);
    }

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
