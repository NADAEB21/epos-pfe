package tn.epos.scoring_service.entities;

import jakarta.persistence.*;
import lombok.*;

import java.util.List;

@Entity
@Table(name = "etudiants")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
public class Etudiant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String numero_inscription;
    private String nom;
    private String prenom;
    private String email; 

    @OneToMany(mappedBy = "etudiant")
    private List<ExamenParticipation> participations;

    /**
     * #351 — casse canonique à CHAQUE écriture, quel que soit le chemin qui y
     * mène (service, import, ou un futur appelant qui oublierait de passer par
     * {@code EtudiantService.normaliserNumero}). Filet de sécurité, pas le seul
     * contrôle : la vérification de doublon doit avoir lieu AVANT persist
     * (voir {@code EtudiantService.verifierNumeroDisponible}), ce callback ne
     * fait que garantir que ce qui atterrit en base est toujours normalisé,
     * même via un save() direct.
     */
    @PrePersist
    @PreUpdate
    protected void normaliserNumeroInscription() {
        if (this.numero_inscription != null) {
            this.numero_inscription = this.numero_inscription.trim().toUpperCase();
        }
    }
}
