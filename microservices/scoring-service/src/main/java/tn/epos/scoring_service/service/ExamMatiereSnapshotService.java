package tn.epos.scoring_service.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.epos.common.exception.BusinessException;
import tn.epos.scoring_service.entities.ExamMatiereSnapshot;
import tn.epos.scoring_service.repositories.ExamMatiereSnapshotRepository;

/**
 * Répond localement à « à quelle matière appartient l'examen N ? » — #274, ADR-0015.
 *
 * <p>Lecture locale d'abord ; matérialisation <b>stricte</b> sinon. Une fois la ligne écrite,
 * l'autorisation par matière ne fait plus AUCUN appel réseau : c'est toute la raison d'être de
 * l'instantané, et ce qui permet aux écritures du jour J de survivre à une panne d'exam-service.
 *
 * <p><b>Pas de jumeau dégradant.</b> {@link ExamDefinitionSnapshotService} en a un
 * ({@code resolveStationNomPourAffichage}) parce qu'un intitulé manquant est de la métadonnée
 * d'affichage. Ici il n'y a rien à dégrader : une matière inconnue ne peut pas être remplacée par
 * une matière plausible sans risquer d'autoriser le mauvais responsable. Le chemin est
 * exclusivement un chemin d'autorisation, donc strict par construction.
 */
@Service
@RequiredArgsConstructor
public class ExamMatiereSnapshotService {

    private final ExamMatiereSnapshotRepository matiereSnapshotRepository;

    /**
     * @see ExamMatiereMaterialiser pourquoi l'écriture vit dans un bean distinct
     *      ({@code REQUIRES_NEW} n'est appliqué qu'à travers le proxy Spring).
     */
    private final ExamMatiereMaterialiser materialiser;

    /**
     * Matière de l'examen. Fige la valeur à la première demande.
     *
     * @throws BusinessException si la matière n'est pas encore figée et qu'exam-service ne peut
     *                           pas la fournir. À laisser remonter : l'appelant doit être refusé,
     *                           jamais autorisé « par défaut ».
     */
    @Transactional
    public Long resolveMatiereId(Long examenId) {
        if (examenId == null) {
            throw new BusinessException(
                    "Examen inconnu : matière irrésoluble, écriture refusée (#274).");
        }
        return matiereSnapshotRepository.findByExamenId(examenId)
                .map(ExamMatiereSnapshot::getMatiereId)
                .orElseGet(() -> materialiser.materialiseMatiere(examenId).getMatiereId());
    }
}
