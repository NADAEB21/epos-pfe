package tn.epos.scoring_service.entities;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.util.List;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
public class StudentGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Integer numeroGroupe;

    @ManyToOne
    @JoinColumn(name = "lot_id")
    private Lot lot;

    @OneToMany(mappedBy = "studentGroup", cascade = CascadeType.ALL)
    @JsonIgnore
    private List<Rotation> rotations;
}