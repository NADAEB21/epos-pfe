package tn.epos.common.security.revocation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import tn.epos.common.sync.InternalListSyncClient;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * #306 — le poller : rapatrie périodiquement la liste de révocation depuis auth-service et
 * remplace l'instantané local. Transport et posture de panne : {@link InternalListSyncClient}
 * (extrait d'ici quand #303 a eu besoin de la même boucle) — ne vit ici que le PARSING.
 *
 * <p>Fenêtre tolérée : une révocation est un acte de sécurité, l'intervalle (30 s par
 * défaut) est le compromis retenu par #306 ; en cas de panne d'auth, la fenêtre résiduelle
 * est bornée par la durée de vie du jeton — exactement l'état d'avant #306.
 */
public final class RevocationSyncClient extends InternalListSyncClient {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TokenRevocationList target;

    public RevocationSyncClient(String authBaseUrl, String jwtSecret,
                                TokenRevocationList target, Duration interval) {
        super(authBaseUrl, "/internal/revocations", jwtSecret, interval, "revocation-sync",
                "#306", "liste de révocation",
                "les révocations récentes ne sont pas encore appliquées ici "
                        + "(fenêtre bornée par l'expiration des jetons)");
        this.target = target;
    }

    /** Corps attendu : {@code {"success":true,"data":[{"userId":60,"invalidBeforeEpochMs":...}]}}. */
    @Override
    protected void applySnapshot(String body) throws Exception {
        JsonNode data = objectMapper.readTree(body).path("data");
        Map<Long, Instant> snapshot = new HashMap<>();
        for (JsonNode row : data) {
            snapshot.put(row.path("userId").asLong(),
                    Instant.ofEpochMilli(row.path("invalidBeforeEpochMs").asLong()));
        }
        target.replaceAll(snapshot);
    }

    @Override
    protected int targetSize() {
        return target.size();
    }
}
