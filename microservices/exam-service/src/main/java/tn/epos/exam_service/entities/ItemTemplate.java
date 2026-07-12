package tn.epos.exam_service.entities;

import jakarta.persistence.*;
import lombok.*;
import tn.epos.exam_service.enums.TypeItem;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "item_templates")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ItemTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String libelle;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TypeItem type;

    @Column(nullable = false)
    private Double ponderation;

    private Double valeurMax;

    private String categorie;

    private Integer ordre;

    // Corrigé / clé de réponse (#162) — repris tel quel lors de l'application
    // d'un template à une station.
    @Column(name = "valeur_attendue")
    private Double valeurAttendue;

    @Column(name = "conditions_attendues", length = 1000)
    private String conditionsAttendues;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "template_id")
    private GrilleTemplate template;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private ItemTemplate parent;

    @OneToMany(mappedBy = "parent", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<ItemTemplate> children = new ArrayList<>();

    public void addChild(ItemTemplate child) {
        children.add(child);
        child.setParent(this);
    }
}
