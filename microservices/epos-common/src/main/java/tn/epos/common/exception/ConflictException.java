package tn.epos.common.exception;

/**
 * Une écriture entre en conflit avec une ressource existante (clé dupliquée,
 * course sur une contrainte unique). Mappée en HTTP 409 par
 * GlobalExceptionHandler — au même niveau que BusinessException (400) et
 * ResourceNotFoundException (404).
 */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}