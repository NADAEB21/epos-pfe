package tn.epos.exam_service.exception;

public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String resource, Long id) {
        super(resource + " introuvable avec l'id : " + id);
    }

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
