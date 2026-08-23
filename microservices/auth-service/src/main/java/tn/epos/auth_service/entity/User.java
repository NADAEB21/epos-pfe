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

    /**
     * #306 — les jetons d'accès émis AVANT cet instant sont révoqués, partout, dans la
     * minute (distribution par {@code /internal/revocations}). Posé par le retrait d'accès,
     * un changement de rôles, un changement/réinitialisation de mot de passe. Null = jamais
     * révoqué. Ne PAS confondre avec {@link #isActive} : l'estampille tue les jetons déjà
     * émis, {@code isActive} interdit d'en obtenir de nouveaux.
     */
    @Column(name = "tokens_invalid_before")
    private LocalDateTime tokensInvalidBefore;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<UserRole> roles = new ArrayList<>();

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        canonicaliserEmail();
    }

    /**
     * #285 — l'e-mail est canonicalisé en minuscules à CHAQUE écriture, création comme
     * modification.
     *
     * <p><b>Pourquoi ici et pas seulement au service.</b> Une normalisation posée dans
     * {@code UserService.createUser} ne couvre que la porte qu'on a pensé à couvrir. Ici, aucun
     * chemin d'écriture ne peut l'oublier — ni un import en lot, ni un futur endpoint, ni un test
     * qui construit l'entité à la main. C'est la même raison qui a fait descendre la garde de
     * périmètre dans la couche service côté scoring plutôt que de la répéter par contrôleur.
     *
     * <p>Le défaut que ça ferme, et il s'est produit : deux comptes pour
     * {@code s34-eval@epos.tn}, l'un en majuscules, constatés en base le 2026-08-12. Une faute de
     * frappe sur la casse fourchait l'identité d'une personne — deux jeux de rôles, deux pistes
     * d'audit, et rien pour signaler que c'était le même être humain.
     *
     * <p>La lecture est rendue insensible séparément, par {@code UserRepository} : canonicaliser
     * à l'écriture ne suffirait pas, il faut aussi que « Admin@epos.tn » retrouve la ligne (#29).
     * L'index {@code uq_users_email_lower} (V5) ferme enfin la fenêtre de concurrence, qu'aucune
     * garde Java ne peut couvrir.
     */
    @PreUpdate
    protected void onUpdate() {
        canonicaliserEmail();
    }

    private void canonicaliserEmail() {
        if (email != null) {
            email = email.trim().toLowerCase(java.util.Locale.ROOT);
        }
    }
}
