package tn.epos.auth_service.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "matieres")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Matiere {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Size(max = 20)
    @Column(nullable = false, unique = true, length = 20)
    private String code;

    @NotBlank
    @Size(max = 100)
    @Column(nullable = false, length = 100)
    private String libelle;

    /**
     * #134 — false = matière retirée du catalogue actif. Jamais de DELETE :
     * matiere_id traverse les services en clé logique sans contrainte SQL
     * (ADR-0006), une suppression orphelinerait les examens passés.
     */
    @Builder.Default
    @Column(nullable = false)
    private Boolean active = true;

    /** #134 — provenance du retrait, même exigence que users.deactivated_* (#289). */
    @Column(name = "retired_at")
    private LocalDateTime retiredAt;

    @Column(name = "retired_by")
    private Long retiredBy;

    @Column(name = "retirement_motif", length = 500)
    private String retirementMotif;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
