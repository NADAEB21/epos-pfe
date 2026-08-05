package tn.epos.auth_service.dto;

import java.util.List;

/**
 * #134 — verdict d'un import en lot, ligne par ligne. {@code ligne} est
 * l'index 1-basé dans l'envoi, pour que le message d'erreur pointe la ligne
 * du tableau collé par l'utilisateur.
 */
public record MatiereImportResult(
        int crees,
        int doublons,
        int erreurs,
        List<Row> rows
) {

    public record Row(int ligne, String code, Statut statut, String message) {}

    public enum Statut { CREATED, DUPLICATE, ERROR }
}
