package tn.epos.auth_service.exception;

public class UnauthorizedDelegationException extends RuntimeException {

    public UnauthorizedDelegationException(String message) {
        super(message);
    }
}
