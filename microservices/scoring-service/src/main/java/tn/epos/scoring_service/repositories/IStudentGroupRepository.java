package tn.epos.scoring_service.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import tn.epos.scoring_service.entities.StudentGroup;

import java.util.List;

@Repository
public interface IStudentGroupRepository extends JpaRepository<StudentGroup, Long> {
    // Optionnel : trouver les groupes appartenant à un lot spécifique
    List<StudentGroup> findByLotId(Long lotId);
}