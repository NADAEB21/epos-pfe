package tn.epos.exam_service.catalogue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import tn.epos.common.sync.InternalListSyncClient;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * #303 — rapatrie périodiquement les matières RETIRÉES depuis auth-service
 * ({@code /internal/matieres-retirees}) et remplace l'instantané local. Transport et
 * posture de panne : {@link InternalListSyncClient} — le socle du poller de révocation
 * #306, partagé au lieu d'être cloné ; ne vit ici que le PARSING.
 *
 * <p>Fenêtre tolérée : une fermeture de matière est un acte de catalogue, rare et
 * réversible — 30 s de latence ne changent rien (contrairement à une révocation de
 * jeton, acte de sécurité, dont la fenêtre se minimise).
 */
public final class RetiredMatiereSyncClient extends InternalListSyncClient {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RetiredMatiereList target;

    public RetiredMatiereSyncClient(String authBaseUrl, String jwtSecret,
                                    RetiredMatiereList target, Duration interval) {
        super(authBaseUrl, "/internal/matieres-retirees", jwtSecret, interval,
                "matiere-retiree-sync",
                "#303", "liste des matières retirées",
                "les fermetures récentes du catalogue ne sont pas encore appliquées ici");
        this.target = target;
    }

    /** Corps attendu : {@code {"success":true,"data":[{"id":10,"libelle":"Pharmacognosie"}]}}. */
    @Override
    protected void applySnapshot(String body) throws Exception {
        JsonNode data = objectMapper.readTree(body).path("data");
        Map<Long, String> snapshot = new HashMap<>();
        for (JsonNode row : data) {
            snapshot.put(row.path("id").asLong(), row.path("libelle").asText(""));
        }
        target.replaceAll(snapshot);
    }

    @Override
    protected int targetSize() {
        return target.size();
    }
}
