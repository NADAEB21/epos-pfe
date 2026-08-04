package tn.epos.auth_service.repository;

import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tn.epos.auth_service.entity.RoleType;
import tn.epos.auth_service.entity.User;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    @Query("SELECT DISTINCT u FROM User u JOIN UserRole ur ON ur.user = u WHERE ur.role = :role")
    List<User> findByRole(@Param("role") RoleType role);

    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("UPDATE User u SET u.failedLoginAttempts = u.failedLoginAttempts + 1 WHERE u.id = :userId")
    void incrementFailedAttempts(@Param("userId") Long userId);

    /**
     * Connexion réussie : le compteur d'échecs ET le verrou tombent ensemble.
     * #294 — sans la remise à zéro de {@code lockCount}, le backoff continuerait
     * d'escalader pour quelqu'un qui a simplement retrouvé son mot de passe.
     */
    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("UPDATE User u SET u.failedLoginAttempts = 0, u.lockedUntil = null, u.lockCount = 0 "
            + "WHERE u.id = :userId")
    void resetFailedAttempts(@Param("userId") Long userId);

    /**
     * #294 — pose un verrou TEMPORAIRE. Remplace l'ancien {@code lockAccount},
     * qui écrivait {@code isActive=false} : un verrou définitif, indiscernable
     * d'une désactivation administrative, et sans aucun retour possible.
     *
     * <p>Le compteur d'échecs repart de zéro : le cycle suivant se compte à
     * neuf, tandis que {@code lockCount} garde la mémoire de l'escalade.
     */
    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("UPDATE User u SET u.lockedUntil = :until, u.lockCount = u.lockCount + 1, "
            + "u.failedLoginAttempts = 0 WHERE u.id = :userId")
    void applyTemporaryLock(@Param("userId") Long userId,
                            @Param("until") java.time.LocalDateTime until);

    /** #294 — état du verrou, hors cache JPA (même raison que le compteur). */
    @Query("SELECT u.lockCount FROM User u WHERE u.id = :userId")
    @QueryHints(@QueryHint(name = "jakarta.persistence.cache.retrieveMode", value = "BYPASS"))
    int getLockCount(@Param("userId") Long userId);

    @Query("SELECT u.failedLoginAttempts FROM User u WHERE u.id = :userId")
    @QueryHints(@QueryHint(name = "jakarta.persistence.cache.retrieveMode", value = "BYPASS"))
    int getFailedLoginAttempts(@Param("userId") Long userId);
}
