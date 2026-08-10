package tn.epos.scoring_service.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tn.epos.scoring_service.client.ExamServiceClient;
import tn.epos.scoring_service.entities.ExamMatiereSnapshot;
import tn.epos.scoring_service.repositories.ExamMatiereSnapshotRepository;

/**
 * <b>ADR-0015</b> / #274 — écriture de la matière figée d'un examen, isolée dans son propre bean.
 *
 * <p><b>Pourquoi un bean séparé et non une méthode de {@link ExamMatiereSnapshotService}.</b>
 * {@code REQUIRES_NEW} est appliqué par le <i>proxy</i> Spring : un appel {@code this.materialise(…)}
 * depuis le même bean ne le traverse pas, l'annotation devient <b>inerte</b>, et deux garanties
 * tombent en silence — le rattrapage {@code catch (DataIntegrityViolationException)} s'exécuterait
 * dans une transaction déjà condamnée {@code rollback-only}, et « la matière figée est un fait
 * durable » redeviendrait faux (annulée avec la transaction métier). C'est la même raison, et le
 * même montage, que {@link ExamDefinitionMaterialiser}.
 *
 * <p>⚠️ <b>Ne jamais figer une valeur de repli.</b> Une matière devinée autoriserait durablement
 * le mauvais responsable : en cas d'échec amont on n'écrit rien et l'appelant échoue.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExamMatiereMaterialiser {

    private final ExamServiceClient examServiceClient;
    private final ExamMatiereSnapshotRepository matiereSnapshotRepository;

    /**
     * Fige la matière de l'examen et rend son identifiant.
     *
     * <p>La ligne écrite est un fait sur l'EXAMEN, pas sur l'appelant : la figer puis refuser
     * l'appelant est cohérent — elle servira au prochain appel légitime. En pratique le cas ne
     * se présente presque jamais, exam-service refusant lui-même un responsable étranger sur
     * {@code GET /api/examens/{id}} avant que quoi que ce soit ne soit écrit.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ExamMatiereSnapshot materialiseMatiere(Long examenId) {
        Long matiereId = examServiceClient.getMatiereIdStrict(examenId);

        ExamMatiereSnapshot snapshot = ExamMatiereSnapshot.builder()
                .examenId(examenId)
                .matiereId(matiereId)
                .build();
        try {
            ExamMatiereSnapshot saved = matiereSnapshotRepository.save(snapshot);
            log.info("ADR-0015 / #274 : examen {} rattaché à la matière {} (figé)",
                    examenId, matiereId);
            return saved;
        } catch (DataIntegrityViolationException race) {
            // Deux écritures concurrentes ont figé le même examen : la contrainte UNIQUE a
            // tranché. Le perdant relit le gagnant — les deux valeurs sont identiques, la
            // matière d'un examen ne change pas.
            return matiereSnapshotRepository.findByExamenId(examenId)
                    .orElseThrow(() -> race);
        }
    }
}
