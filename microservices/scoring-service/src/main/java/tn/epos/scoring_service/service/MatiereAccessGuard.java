package tn.epos.scoring_service.service;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import tn.epos.scoring_service.config.MatiereScopeChecker;

/**
 * Le point d'entrée UNIQUE des écritures bornées par matière — #274.
 *
 * <p><b>Pourquoi une seule porte.</b> Le défaut corrigé par #274 était exactement l'inverse :
 * deux chemins vers la même donnée, deux réponses opposées ({@code POST
 * /evaluateur/notations/saisir} refusait, {@code PUT /api/notation-items/152} acceptait et faisait
 * passer la note de 8 à 3). Séparer « résoudre la matière » de « vérifier le périmètre » ferait
 * réapparaître la même classe de défaut, un oubli de vérification suffisant. Les services
 * appellent donc {@link #checkExamenAccess(Long)}, jamais les deux moitiés.
 *
 * <p><b>Ce qu'il ne fait pas : remonter les chaînes d'entités.</b> Le service appelant tient déjà
 * ses entités chargées et connaît son propre chemin vers l'examen ({@code Lot.examenId} direct,
 * {@code Notation → assignment → rotation → studentGroup → lot} à quatre maillons). Lui faire
 * passer un {@code examenId} garde cette classe libre de toute connaissance du modèle — c'est le
 * choix déjà argumenté pour {@code EvaluateurScopeChecker} (ADR-0007).
 */
@Service
@RequiredArgsConstructor
public class MatiereAccessGuard {

    private final ExamMatiereSnapshotService matiereSnapshot;
    private final MatiereScopeChecker matiereScopeChecker;

    /**
     * Exige que l'appelant soit habilité sur la matière de cet examen, sinon 403.
     *
     * <p>Échec <b>fermé</b> sur {@code examenId == null} : une chaîne d'entités rompue (lot
     * détaché, notation sans affectation) ne doit jamais devenir une autorisation. On refuse
     * avant même de tenter la résolution, pour que la cause soit lisible dans le message.
     *
     * @throws AccessDeniedException                     matière hors périmètre (403)
     * @throws tn.epos.common.exception.BusinessException matière non figée et exam-service
     *                                                    injoignable — cas assumé : refuser est
     *                                                    réversible, autoriser hors périmètre ne
     *                                                    l'est pas (ADR-0015)
     */
    public void checkExamenAccess(Long examenId) {
        if (examenId == null) {
            throw new AccessDeniedException(
                    "Accès interdit : examen de rattachement introuvable, "
                            + "périmètre de matière invérifiable (#274).");
        }
        matiereScopeChecker.checkAccess(matiereSnapshot.resolveMatiereId(examenId));
    }
}
