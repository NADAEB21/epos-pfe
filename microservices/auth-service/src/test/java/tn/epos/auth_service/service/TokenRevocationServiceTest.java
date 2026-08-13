package tn.epos.auth_service.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tn.epos.auth_service.repository.UserRepository;
import tn.epos.common.security.revocation.TokenRevocationList;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * #306 — l'écrivain des révocations : estampille en base + liste locale d'auth mise à jour
 * dans le même geste, et distribution bornée à la fenêtre utile en ÉPOQUE (pas de zone).
 */
@ExtendWith(MockitoExtension.class)
class TokenRevocationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-13T10:00:00Z");
    private static final long DAY_MS = 86_400_000L;

    @Mock private UserRepository userRepository;

    private final TokenRevocationList localList = new TokenRevocationList();
    private final Clock clock = Clock.fixed(NOW, ZoneId.of("UTC"));

    private TokenRevocationService service() {
        return new TokenRevocationService(userRepository, localList, clock, DAY_MS);
    }

    @Test
    @DisplayName("revokeIssuedTokens : estampille en base ET liste locale dans le même geste")
    void revoqueEnBaseEtEnMemoire() {
        LocalDateTime stamp = service().revokeIssuedTokens(60L, "test");

        assertThat(stamp).isEqualTo(LocalDateTime.now(clock));
        verify(userRepository).stampTokensInvalidBefore(60L, stamp);
        // Latence ZÉRO chez l'émetteur : un jeton émis avant l'acte est mort
        // immédiatement pour auth-service, sans attendre un tour de synchronisation.
        assertThat(localList.isRevoked(60L, NOW.minusSeconds(1))).isTrue();
        assertThat(localList.isRevoked(60L, NOW.plusSeconds(1))).isFalse();
    }

    @Test
    @DisplayName("recentRevocations : époque en millisecondes calculée dans la zone de l'horloge")
    void distributionEnEpoque() {
        UserRepository.RevocationRow row = new UserRepository.RevocationRow() {
            @Override public Long getUserId() { return 60L; }
            @Override public LocalDateTime getInvalidBefore() { return LocalDateTime.now(clock); }
        };
        when(userRepository.findRevocationsSince(any())).thenReturn(List.of(row));

        var entries = service().recentRevocations();

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).userId()).isEqualTo(60L);
        // 2026-08-13T10:00:00Z — si la zone était perdue, ce serait décalé d'heures :
        // la famille de bug déjà payée sur le verrou temporaire (session 34).
        assertThat(entries.get(0).invalidBeforeEpochMs()).isEqualTo(NOW.toEpochMilli());
    }

    @Test
    @DisplayName("refreshFromDatabase : la base est la source de vérité — une entrée disparue cesse de révoquer")
    void relectureRemplaceLaListe() {
        localList.put(60L, NOW);
        when(userRepository.findRevocationsSince(any())).thenReturn(List.of());

        service().refreshFromDatabase();

        assertThat(localList.isRevoked(60L, NOW.minusSeconds(1))).isFalse();
    }
}
