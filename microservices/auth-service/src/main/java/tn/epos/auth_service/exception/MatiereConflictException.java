package tn.epos.auth_service.exception;

/**
 * #134 — conflit d'état sur le catalogue des matières : code déjà pris
 * (comparé sans tenir compte de la casse, leçon de #285), matière déjà
 * retirée, ou déjà active. Mappée sur 409.
 */
public class MatiereConflictException extends RuntimeException {
    public MatiereConflictException(String message) {
        super(message);
    }
}
