package tn.epos.auth_service.exception;

/** #134 — la matière visée n'existe pas. Mappée sur 404. */
public class MatiereNotFoundException extends RuntimeException {
    public MatiereNotFoundException(String message) {
        super(message);
    }
}
