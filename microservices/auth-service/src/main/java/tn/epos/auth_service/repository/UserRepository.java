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

    /**
     * #29 — recherche INSENSIBLE À LA CASSE. « Admin@epos.tn » doit retrouver
     * « admin@epos.tn ».
     *
     * <p>C'était une requête dérivée nue, donc sensible à la casse : se connecter avec une
     * majuscule échouait sur un mot de passe pourtant juste. Et la demande de réinitialisation,
     * volontairement anti-énumération, répondait <b>200</b> sans jamais envoyer de courriel — la
     * personne restait dehors sans apprendre pourquoi.
     *
     * <p>Le nom {@code findByEmail} est conservé : les appelants n'ont rien à changer, et surtout
     * ils ne peuvent plus se tromper — la normalisation n'est plus quelque chose qu'un appelant
     * doit penser à faire. L'écriture, elle, est canonicalisée par {@code User.@PrePersist} /
     * {@code @PreUpdate}.
     */
    @Query("SELECT u FROM User u WHERE lower(u.email) = lower(:email)")
    Optional<User> findByEmail(@Param("email") String email);

    /**
     * #285 — unicité vérifiée SANS la casse : sinon « Admin@epos.tn » passe alors que
     * « admin@epos.tn » existe, et l'identité d'une personne se fourche en deux comptes.
     *
     * <p>Cette garde ne suffit pas à elle seule — deux créations simultanées la traversent
     * toutes les deux. C'est l'index {@code uq_users_email_lower} (V5) qui ferme la fenêtre ;
     * celle-ci sert à rendre le refus lisible (409) plutôt qu'une violation de contrainte brute.
     */
    @Query("SELECT COUNT(u) > 0 FROM User u WHERE lower(u.email) = lower(:email)")
    boolean existsByEmail(@Param("email") String email);

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
