package tn.epos.auth_service.exception;

/**
 * L'adresse demandée à la création appartient à un compte dont l'accès a été
 * RETIRÉ (#289, ADR-0023). Le retrait n'efface rien — l'historique de notation
 * pointe vers ce compte —, l'adresse reste donc prise.
 *
 * <p>Le remède n'est ni d'ajouter un rôle (le compte resterait fermé) ni de
 * créer un second compte (une adresse = une personne), mais de RÉTABLIR
 * celui-ci. L'exception porte son id pour que l'écran puisse le proposer
 * directement, au lieu d'un refus sans issue.
 *
 * <p>Sous-classe de {@link EmailAlreadyExistsException} : c'est toujours un
 * conflit d'adresse (409) — seul le remède diffère.
 */
public class CompteRetireException extends EmailAlreadyExistsException {

    /** Valeur de {@code data.code} dans la réponse 409 — lue par le web. */
    public static final String CODE = "COMPTE_RETIRE";

    private final Long userId;

    public CompteRetireException(Long userId, String message) {
        super(message);
        this.userId = userId;
    }

    /** Le compte retiré qui porte l'adresse — celui à rétablir. */
    public Long getUserId() {
        return userId;
    }
}
