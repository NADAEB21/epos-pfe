package tn.epos.scoring_service.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tn.epos.scoring_service.entities.NotationItem;
import tn.epos.scoring_service.exception.ResourceNotFoundException;
import tn.epos.scoring_service.repositories.INotationItemRepository;

import java.util.List;
import java.util.Optional;

@Service
public class NotationItemService {

    @Autowired
    private INotationItemRepository repository;

    public List<NotationItem> findAll() {
        return repository.findAll();
    }

    public List<NotationItem> findByNotation(Long notationId) {
        return repository.findByNotationId(notationId);
    }

    public Optional<NotationItem> findById(Long id) {
        return repository.findById(id);
    }

    public NotationItem save(NotationItem item) {
        return repository.save(item);
    }

    public void delete(Long id) {
        repository.deleteById(id);
    }

    public NotationItem update(Long id, NotationItem details) {
        return repository.findById(id).map(item -> {
            item.setItem_id(details.getItem_id());
            item.setValeur(details.getValeur());
            item.setCommentaire(details.getCommentaire());
            item.setNotation(details.getNotation());
            return repository.save(item);
        }).orElseThrow(() -> new ResourceNotFoundException("NotationItem non trouvé avec l'id : " + id));
    }
}