package tn.epos.scoring_service.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.epos.scoring_service.entities.ExamenParticipation;
import tn.epos.scoring_service.repositories.IExamenParticipationRepository;

import java.util.List;
import java.util.Optional;

/**
 * Inscriptions d'étudiants à un examen.
 *
 * <p><b>#274 — les écritures sont bornées à la matière de l'examen.</b> Inscrire, noter ou
 * désinscrire un candidat est un acte pédagogique sur UNE épreuve : il appartient au titulaire de
 * la matière. Les lectures restent ouvertes (ADR-0018 D5).
 *
 * <p>⚠️ À ne pas confondre avec {@code EtudiantService} : l'étudiant est un enregistrement
 * d'<b>annuaire facultaire</b>, sans lien d'examen, donc structurellement non rattachable à une
 * matière. Il reste hors du périmètre de #274 — c'est une limite énoncée, pas un oubli.
 */
@Service
public class ExamenParticipationService {

    @Autowired
    private IExamenParticipationRepository repository;

    @Autowired
    private MatiereAccessGuard matiereAccessGuard;

    public List<ExamenParticipation> getAll() {
        return repository.findAll();
    }

    public List<ExamenParticipation> getByExamenId(Long examenId) {
        return repository.findByExamenId(examenId);
    }

    public Optional<ExamenParticipation> getById(Long id) {
        return repository.findById(id);
    }

    /** Création d'une inscription. La modification passe par {@link #update(Long, Float, Boolean)}. */
    public ExamenParticipation save(ExamenParticipation participation) {
        matiereAccessGuard.checkExamenAccess(participation.getExamen_id());
        return repository.save(participation);
    }

    /**
     * Modifie note et présence — <b>la garde AVANT la mutation</b>, et c'est tout l'intérêt.
     *
     * <p><b>Le défaut que cette méthode corrige, mesuré en direct le 2026-08-10.</b> Le contrôleur
     * faisait {@code getById(id)} → {@code setNote(...)} → {@code save(...)}, la garde vivant dans
     * {@code save}. L'appel répondait bien <b>403</b>… et la valeur changeait quand même en base :
     * note 3,25 → 19, présence false → true. Un refus qui persiste l'écriture est pire qu'une
     * absence de garde, parce qu'il se lit comme un succès de sécurité.
     *
     * <p><b>Pourquoi.</b> {@code findById} rattache l'entité à la session (open-in-view), le
     * contrôleur la rend SALE, puis la garde appelle {@code resolveMatiereId}, qui est
     * {@code @Transactional} : son commit FLUSHE la session — donc l'entité modifiée — avant que
     * {@code checkAccess} ne lève. La garde était elle-même le déclencheur du flush.
     *
     * <p><b>La règle générale à retenir :</b> ne jamais salir une entité managée avant d'avoir
     * vérifié le droit d'écrire. Les autres services le faisaient déjà dans le bon ordre
     * ({@code LotService}, {@code StudentGroupService}, {@code NotationItemService} — vérifiés en
     * direct, aucune fuite) ; seul ce chemin-ci mutait dans le contrôleur. {@code @Transactional}
     * ici ajoute la ceinture : un refus annule la transaction.
     *
     * @return vide si l'inscription n'existe pas (le contrôleur en fait un 404)
     */
    @Transactional
    public Optional<ExamenParticipation> update(Long id, Float note, Boolean estPresent) {
        return repository.findById(id).map(existing -> {
            matiereAccessGuard.checkExamenAccess(existing.getExamen_id());
            existing.setNote(note);
            existing.setEst_present(estPresent);
            return repository.save(existing);
        });
    }

    public void delete(Long id) {
        // Charger avant : `deleteById` ne révèle pas l'examen, donc ne permet aucun contrôle.
        repository.findById(id).ifPresent(p -> {
            matiereAccessGuard.checkExamenAccess(p.getExamen_id());
            repository.delete(p);
        });
    }
}