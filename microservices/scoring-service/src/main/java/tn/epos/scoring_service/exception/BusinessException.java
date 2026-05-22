package tn.epos.scoring_service.exception;

/**
 * Thrown when a request is well-formed but violates a business rule
 * (e.g. attempting to modify a locked resource). Mapped to HTTP 400 by
 * {@link GlobalExceptionHandler}. Mirror of the exam-service exception class.
 * See issue #63.
 */
public class BusinessException extends RuntimeException {

    public BusinessException(String message) {
        super(message);
    }
}
