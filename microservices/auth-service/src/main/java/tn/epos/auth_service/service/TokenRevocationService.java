package tn.epos.auth_service.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tn.epos.auth_service.repository.UserRepository;
import tn.epos.common.security.revocation.TokenRevocationList;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * #306 — le point d'écriture des révocations, et la copie locale d'auth-service.
 *
 * <p><b>Écriture.</b> {@link #revokeIssuedTokens} pose l'estampille « les jetons émis avant
 * MAINTENANT sont morts ». Elle est appelée par tout acte de sécurité : retrait d'accès,
 * changement de rôles, changement et réinitialisation de mot de passe. Elle s'exécute en
 * {@code REQUIRES_NEW} : la mort des jetons est un fait de sécurité qui doit survivre même si
 * la transaction métier appelante échoue APRÈS l'acte — la direction conservatrice.
 *
 * <p><b>Lecture locale.</b> auth-service protège ses propres endpoints (/me, /users…) avec la
 * même {@link TokenRevocationList} que les autres services — mais la sienne est nourrie en
 * direct : synchronement au moment de l'acte (latence zéro chez l'émetteur), et par relecture
 * périodique de la base (rattrape un redémarrage ou une écriture concurrente).
 *
 * <p><b>Distribution.</b> {@link #recentRevocations} sert {@code GET /internal/revocations} :
 * uniquement les estampilles plus récentes que la durée de vie maximale d'un jeton (+ marge) —
 * les plus anciennes ne peuvent plus tuer que des jetons déjà expirés.
 */
@Service
@Slf4j
public class TokenRevocationService {

    /** Marge au-delà de l'expiration : dérive d'horloge entre conteneurs, jamais critique. */
    private static final long WINDOW_SLACK_MS = 3_600_000L;

    private final UserRepository userRepository;
    private final TokenRevocationList localList;
    private final Clock clock;
    private final long windowMs;

    public TokenRevocationService(UserRepository userRepository,
                                  TokenRevocationList localList,
                                  Clock clock,
                                  @Value("${jwt.access-token-expiry-ms:86400000}") long accessTokenExpiryMs) {
        this.userRepository = userRepository;
        this.localList = localList;
        this.clock = clock;
        this.windowMs = accessTokenExpiryMs + WINDOW_SLACK_MS;
    }

    /**
     * Tue tous les jetons d'accès déjà émis pour cet utilisateur. L'effet est local-immédiat
     * (la liste d'auth est mise à jour dans l'appel) et distribué sous un tour de
     * synchronisation pour la gateway, exam et scoring.
     *
     * <p>⚠️ <b>Contrat pour l'appelant qui tient un {@code User} MANAGÉ et le sauvegarde
     * ensuite</b> (retrait, mot de passe) : reporter la valeur retournée sur l'entité
     * ({@code user.setTokensInvalidBefore(stamp)}). Hibernate écrit TOUTES les colonnes au
     * flush — une entité restée à null écraserait l'estampille que cette méthode vient de
     * poser (famille du piège #215 : un UPDATE d'entité partielle annule une écriture ciblée).
     *
     * @return l'estampille posée, à reporter sur toute entité managée encore en vol.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public LocalDateTime revokeIssuedTokens(Long userId, String pourquoi) {
        LocalDateTime now = LocalDateTime.now(clock);
        userRepository.stampTokensInvalidBefore(userId, now);
        localList.put(userId, toInstant(now));
        log.info("#306 : jetons émis avant {} révoqués pour l'utilisateur {} ({})",
                now, userId, pourquoi);
        return now;
    }

    /** La liste distribuée — bornée à la fenêtre utile. Époque en millis : aucune ambiguïté de zone. */
    @Transactional(readOnly = true)
    public List<RevocationEntry> recentRevocations() {
        return userRepository.findRevocationsSince(windowStart()).stream()
                .map(r -> new RevocationEntry(r.getUserId(), toInstant(r.getInvalidBefore()).toEpochMilli()))
                .toList();
    }

    /**
     * Relecture périodique : auth-service redémarré (liste mémoire vide) ou écriture passée
     * hors de ce processus (SQL de dépannage) — la base reste la source de vérité.
     */
    @Scheduled(fixedDelayString = "${epos.revocation.refresh-ms:30000}")
    @Transactional(readOnly = true)
    public void refreshFromDatabase() {
        Map<Long, Instant> snapshot = new HashMap<>();
        userRepository.findRevocationsSince(windowStart())
                .forEach(r -> snapshot.put(r.getUserId(), toInstant(r.getInvalidBefore())));
        localList.replaceAll(snapshot);
    }

    private LocalDateTime windowStart() {
        return LocalDateTime.now(clock).minusNanos(windowMs * 1_000_000L);
    }

    /** Les colonnes sont en heure locale du serveur (ADR-0010) : la zone de l'horloge convertit. */
    private Instant toInstant(LocalDateTime local) {
        return local.atZone(clock.getZone()).toInstant();
    }

    public record RevocationEntry(Long userId, long invalidBeforeEpochMs) {
    }
}
