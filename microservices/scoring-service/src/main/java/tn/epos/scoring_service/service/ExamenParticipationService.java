package tn.epos.scoring_service.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tn.epos.scoring_service.entities.ExamenParticipation;
import tn.epos.scoring_service.repositories.IExamenParticipationRepository;

import java.util.List;
import java.util.Optional;

@Service
public class ExamenParticipationService {

    @Autowired
    private IExamenParticipationRepository repository;

    public List<ExamenParticipation> getAll() {
        return repository.findAll();
    }

    public Optional<ExamenParticipation> getById(Long id) {
        return repository.findById(id);
    }

    public ExamenParticipation save(ExamenParticipation participation) {
        return repository.save(participation);
    }

    public void delete(Long id) {
        repository.deleteById(id);
    }
}