package tn.epos.auth_service.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

@Entity
@Table(name = "user_roles")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserRole {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RoleType role;

    /**
     * Nullable. Non-null only for RESPONSABLE_MATIERE (subject-scoped).
     * SUPER_ADMIN and EVALUATEUR must always have null here.
     * FK → matieres.id (see Matiere entity).
     */
    @Column(name = "matiere_id")
    private Long matiereId;

    @PrePersist
    @PreUpdate
    protected void validateMatiereId() {
        if (role == RoleType.RESPONSABLE_MATIERE && matiereId == null) {
            throw new IllegalStateException(
                    "UserRole with role RESPONSABLE_MATIERE must have a non-null matiereId");
        }
        if (role != RoleType.RESPONSABLE_MATIERE && matiereId != null) {
            throw new IllegalStateException(
                    "UserRole with role " + role + " must have a null matiereId");
        }
    }
}
