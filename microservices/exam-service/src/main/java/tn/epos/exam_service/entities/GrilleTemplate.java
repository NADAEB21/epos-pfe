package tn.epos.exam_service.entities;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import tn.epos.exam_service.enums.TypeItem;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "grille_templates")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class GrilleTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String nom;

    private String description;

    @Column(nullable = false)
    private Double noteMax;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @OneToMany(mappedBy = "template", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<ItemTemplate> items = new ArrayList<>();

    public void addItem(ItemTemplate item) {
        items.add(item);
        item.setTemplate(this);
    }
}
