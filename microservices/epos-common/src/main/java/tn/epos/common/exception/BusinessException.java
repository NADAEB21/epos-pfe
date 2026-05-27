package tn.epos.common.exception;

/**
 * Thrown when a request is well-formed but violates a business rule
 * (e.g. attempting to modify a locked resource). Mapped to HTTP 400 by
 * each service's {@code GlobalExceptionHandler}.
 *
 * <p>Extracted into {@code epos-common} as part of #68. Previously
 * duplicated in exam-service and scoring-service (#63/#77).
 */
public class BusinessException extends RuntimeException {

    public BusinessException(String message) {
        super(message);
    }
}
