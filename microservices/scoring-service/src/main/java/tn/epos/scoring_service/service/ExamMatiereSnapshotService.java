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
     * <p><b>{@code readOnly = true} est une garde, pas une optimisation.</b> Cette méthode est
     * appelée par {@code MatiereAccessGuard} <i>avant</i> une écriture métier. En lecture-écriture,
     * son commit FLUSHE la session de l'appelant : si un contrôleur a déjà sali une entité managée,
     * l'écriture partait <b>malgré</b> le refus qui suit. C'est le défaut mesuré le 2026-08-10 sur
     * {@code PUT /api/participations/{id}} — 403 rendu, note 3,25 → 19 écrite. La cause immédiate
     * (muter avant de vérifier) est corrigée dans {@code ExamenParticipationService.update}, mais
     * elle est invisible aux tests unitaires et un futur contrôleur pourrait la réintroduire.
     * {@code readOnly} met Hibernate en {@code FlushMode.MANUAL} : la garde ne peut alors plus
     * flusher la session de personne. Ceinture ET bretelles, pour une classe de défaut qu'aucun
     * mock ne détecte.
     *
     * <p>L'écriture de matérialisation n'est pas gênée : elle vit dans
     * {@link ExamMatiereMaterialiser}, en {@code REQUIRES_NEW}, donc dans sa propre transaction
     * lecture-écriture. Vérifié en direct — instantané vidé, la ligne est bien figée à l'appel
     * suivant.
     *
     * @throws BusinessException si la matière n'est pas encore figée et qu'exam-service ne peut
     *                           pas la fournir. À laisser remonter : l'appelant doit être refusé,
     *                           jamais autorisé « par défaut ».
     */
    @Transactional(readOnly = true)
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
