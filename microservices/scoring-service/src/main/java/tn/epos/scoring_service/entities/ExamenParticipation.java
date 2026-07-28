package tn.epos.scoring_service.entities;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "examen_participations")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
public class ExamenParticipation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Représente le <<FK>> examen_id du diagramme
    private Long examen_id;

    private String num_echantillon;
    private Float note;

    private Boolean est_present;

    // #256 — numéro de ligne du fichier Excel importé : L'ordre officiel du listing,
    // persisté à l'import (et rafraîchi si le fichier est réimporté). La répartition
    // en lots ET la constitution des groupes trient dessus — plus jamais sur id, qui
    // ne coïncidait avec le fichier que par chance. NULL = ajout manuel hors fichier.
    private Integer ordre_import;

    @Column(length = 500)
    private String commentaire;

    // #227 — quand la convocation de cet étudiant a été envoyée par e-mail.
    // NULL = jamais envoyée. Explicitement nommée : "convocationEnvoyeeA" se
    // serait mappée en "convocationenvoyeea" (le piège ouvertA→ouverta, #208).
    @Column(name = "convocation_envoyee_a")
    private java.time.LocalDateTime convocation_envoyee_a;

    // Relation avec Etudiant (le 1 du côté Etudiant et 0..* ici)
    @ManyToOne
    @JoinColumn(name = "etudiant_id")
    private Etudiant etudiant;

    @ManyToOne
    @JoinColumn(name = "lot_id")
    private Lot lot;
}
