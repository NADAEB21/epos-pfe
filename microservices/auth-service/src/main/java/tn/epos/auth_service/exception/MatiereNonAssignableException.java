package tn.epos.auth_service.exception;

/**
 * #134 — la charge utile référence une matière sur laquelle on ne peut pas
 * nommer de responsable : inexistante (avant cette garde, la violation de FK
 * remontait en 500 brut) ou retirée du catalogue. Mappée sur 400 : c'est la
 * requête qui est invalide, pas les droits de l'appelant.
 */
public class MatiereNonAssignableException extends RuntimeException {
    public MatiereNonAssignableException(String message) {
        super(message);
    }
}
