package tn.epos.common.security.revocation;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * #306 — la liste de révocation en mémoire : « les jetons de l'utilisateur N émis avant
 * l'instant T sont morts ».
 *
 * <p><b>Pourquoi une liste et pas une lecture en base par requête.</b> Les quatre options ont
 * été chiffrées dans #306. Une lecture par requête (ou une liste noire Redis) fait de
 * auth-service — ou d'un conteneur de plus sur le PC de la faculté — une dépendance de CHAQUE
 * requête du système : en panne, il faut choisir entre tout laisser passer (la garantie tombe
 * quand on en a besoin) et tout bloquer (la plateforme meurt pendant une épreuve). Cette liste
 * dégrade proprement : si auth devient injoignable, chaque service continue d'appliquer la
 * dernière liste reçue — et comme les révocations NAISSENT dans auth, un auth muet n'a par
 * définition rien de nouveau à distribuer.
 *
 * <p><b>Le contrat.</b> La liste est REMPLACÉE en bloc à chaque synchronisation
 * ({@link #replaceAll}), jamais modifiée entrée par entrée : l'état visible est toujours un
 * instantané cohérent d'une réponse d'auth-service. Elle est bornée par construction — auth ne
 * publie que les révocations plus récentes que la durée de vie maximale d'un jeton, puisqu'un
 * jeton plus vieux est déjà mort d'expiration.
 *
 * <p>La comparaison se fait sur {@code iat} (présent dans chaque jeton émis par auth-service) :
 * révoquer ne distingue pas les appareils, ça invalide TOUT ce qui a été émis avant l'acte —
 * c'est le sens d'un retrait d'accès ou d'un changement de rôles.
 */
public final class TokenRevocationList {

    private final AtomicReference<Map<Long, Instant>> invalidBeforeByUserId =
            new AtomicReference<>(Map.of());

    /**
     * Vrai si un jeton de {@code userId} émis à {@code issuedAt} est révoqué.
     *
     * <p>Un jeton SANS {@code iat} appartenant à un utilisateur listé est traité comme révoqué :
     * on ne peut pas prouver qu'il est postérieur à l'acte, et tous les jetons émis par
     * auth-service portent {@code iat} — son absence est déjà une anomalie.
     */
    public boolean isRevoked(Long userId, Instant issuedAt) {
        if (userId == null) {
            return false;
        }
        Instant invalidBefore = invalidBeforeByUserId.get().get(userId);
        if (invalidBefore == null) {
            return false;
        }
        return issuedAt == null || issuedAt.isBefore(invalidBefore);
    }

    /** Remplace l'intégralité de la liste par l'instantané fourni (copie défensive). */
    public void replaceAll(Map<Long, Instant> snapshot) {
        invalidBeforeByUserId.set(Map.copyOf(snapshot));
    }

    /**
     * Ajoute/renforce UNE entrée sans attendre la prochaine synchronisation. Utilisé par
     * auth-service lui-même, qui apprend la révocation au moment où il l'écrit — le reste du
     * monde l'apprendra au prochain tour de synchronisation.
     */
    public void put(Long userId, Instant invalidBefore) {
        var current = new java.util.HashMap<>(invalidBeforeByUserId.get());
        current.merge(userId, invalidBefore, (a, b) -> a.isAfter(b) ? a : b);
        invalidBeforeByUserId.set(Map.copyOf(current));
    }

    /** Nombre d'entrées actives — pour les logs de synchronisation. */
    public int size() {
        return invalidBeforeByUserId.get().size();
    }
}
