package tn.epos.auth_service.dto;

/**
 * #389 — ce qui s'est passé côté messagerie après la création (ou le renvoi).
 *
 * <p>{@code envoyee} : la remise au fournisseur a réussi (accepté ≠ délivré —
 * un rebond arrive dans la boîte système). {@code simulee} : la messagerie est
 * désactivée ({@code app.mail.enabled=false}) — RIEN n'est parti, et l'écran
 * doit le dire au lieu d'un toast vert (précédent scoring :
 * {@code EnvoiConvocationsResult.simule}). Les deux vrais à la fois = le stub
 * a « envoyé » dans le vide.
 */
public record InvitationStatus(boolean envoyee, boolean simulee) {
}
