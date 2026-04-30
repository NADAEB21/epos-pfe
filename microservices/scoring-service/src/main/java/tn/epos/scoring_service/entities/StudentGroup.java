package tn.epos.scoring_service.entities;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
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