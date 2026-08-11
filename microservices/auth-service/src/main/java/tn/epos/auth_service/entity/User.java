package tn.epos.auth_service.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(nullable = false)
    private String nom;

    @Column(nullable = false)
    private String prenom;

    /**
     * #294 — décision ADMINISTRATIVE seule (départ, retrait). Depuis V2, aucun
     * mécanisme automatique ne l'écrit : le verrouillage anti-force-brute passe
     * par {@link #lockedUntil}. Les deux étaient auparavant confondus, ce qui
     * rendait tout message d'écran faux la moitié du temps.
     */
    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "failed_login_attempts", nullable = false)
    @Builder.Default
    private Integer failedLoginAttempts = 0;

    /** #294 — verrou temporaire : connexion refusée tant que now() < lockedUntil. */
    @Column(name = "locked_until")
    private LocalDateTime lockedUntil;

    /** #294 — verrous consécutifs, pour le backoff exponentiel. */
    @Column(name = "lock_count", nullable = false)
    @Builder.Default
    private Integer lockCount = 0;

    /** #289 — quand le compte a été retiré ; null tant qu'il est actif. */
    @Column(name = "deactivated_at")
    private LocalDateTime deactivatedAt;

    /** #289 — l'administrateur qui a décidé du retrait. */
    @Column(name = "deactivated_by")
    private Long deactivatedBy;

    /** #289 — motif du retrait, obligatoire au moment de l'acte. */
    @Column(name = "deactivation_motif", length = 500)
    private String deactivationMotif;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<UserRole> roles = new ArrayList<>();

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
