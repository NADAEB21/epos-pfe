package tn.epos.exam_service.catalogue;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * #303 — copie locale des matières RETIRÉES du catalogue (id → libellé), rapatriée
 * d'auth-service par {@link RetiredMatiereSyncClient}.
 *
 * <p>Même posture que la liste de révocation #306 : un instantané REMPLACÉ atomiquement à
 * chaque tour de synchronisation, jamais muté en place. Vide au démarrage — tant qu'aucune
 * synchronisation n'a réussi, le service SERT (voir la posture de panne du client) : une
 * fermeture de matière est un acte administratif rare, la fenêtre de 30 s est sans enjeu,
 * et refuser de créer des examens parce qu'auth n'a pas encore répondu serait le piège du
 * matin d'épreuve.
 */
public final class RetiredMatiereList {

    private final AtomicReference<Map<Long, String>> snapshot = new AtomicReference<>(Map.of());

    public void replaceAll(Map<Long, String> retirees) {
        snapshot.set(Map.copyOf(retirees));
    }

    public boolean isRetired(Long matiereId) {
        return matiereId != null && snapshot.get().containsKey(matiereId);
    }

    /** Libellé pour un refus NOMINATIF ; repli sur l'id si inconnu. */
    public String libelleOf(Long matiereId) {
        String libelle = snapshot.get().get(matiereId);
        return libelle != null ? libelle : ("matière #" + matiereId);
    }

    public int size() {
        return snapshot.get().size();
    }
}
