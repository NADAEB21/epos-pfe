package tn.epos.scoring_service.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
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

    /**
     * Sert la création ET la modification (le contrôleur recharge puis re-sauve), donc une
     * seule garde couvre les deux portes.
     */
    public ExamenParticipation save(ExamenParticipation participation) {
        matiereAccessGuard.checkExamenAccess(participation.getExamen_id());
        return repository.save(participation);
    }

    public void delete(Long id) {
        // Charger avant : `deleteById` ne révèle pas l'examen, donc ne permet aucun contrôle.
        repository.findById(id).ifPresent(p -> {
            matiereAccessGuard.checkExamenAccess(p.getExamen_id());
            repository.delete(p);
        });
    }
}