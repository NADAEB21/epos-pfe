package tn.epos.exam_service.dto.response;

import lombok.Data;
import tn.epos.exam_service.enums.StatutExamen;
import java.time.LocalDate;
import java.util.List;

@Data
public class ExamenExportResponse {
    private String nom;
    private String matiere;
    private LocalDate dateExamen;
    private Integer dureeStationMin;
    private Integer nbEtudiantsParStation;
    private String description;
    private List<StationExportResponse> stations;

    @Data
    public static class StationExportResponse {
        private String nom;
        private String type;
        private String description;
        private Integer ordre;
        private GrilleExportResponse grille;
    }

    @Data
    public static class GrilleExportResponse {
        private String nom;
        private Double noteMax;
        private String description;
        private List<ItemExportResponse> items;
    }

    @Data
    public static class ItemExportResponse {
        private String libelle;
        private String type;
        private Double ponderation;
        private Double valeurMax;
        private String categorie;
        private Integer ordre;
    }
}