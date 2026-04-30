package tn.epos.auth_service.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tn.epos.auth_service.entity.RoleType;
import tn.epos.auth_service.entity.UserRole;

import java.util.List;

public interface UserRoleRepository extends JpaRepository<UserRole, Long> {

    List<UserRole> findByUserId(Long userId);

    void deleteByUserId(Long userId);

    boolean existsByUserIdAndRoleAndMatiereId(Long userId, RoleType role, Long matiereId);
}
