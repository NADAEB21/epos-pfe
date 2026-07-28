package tn.epos.scoring_service.dto;

import java.util.List;

/**
 * Bilan d'un envoi de convocations.
 *
 * <p>Même forme d'honnêteté que {@link ImportResult} : un compteur par issue
 * PLUS le détail ligne par ligne, pour que le responsable sache exactement qui
 * a été joint et qui ne l'a pas été. Un « envoyé ! » global cacherait les
 * étudiants sans adresse, qui sont précisément ceux qu'il doit convoquer à la
 * main.
 *
 * <p>{@code simule} = true quand {@code app.mail.enabled} est à false : rien
 * n'est parti. On le dit au lieu de laisser croire à un envoi réussi — c'est
 * l'état par défaut, donc le mensonge serait la règle et non l'exception.
 */
public record EnvoiConvocationsResult(
    int total,
    int envoyes,
    int sansAdresse,
    int echecs,
    boolean simule,
    List<EnvoiLigne> lignes
) {
    /** Issue pour un étudiant. {@code statut} : ENVOYE | SANS_ADRESSE | ECHEC. */
    public record EnvoiLigne(
        Long participationId,
        String nom,
        String prenom,
        String email,
        String statut,
        String message
    ) {}
}
