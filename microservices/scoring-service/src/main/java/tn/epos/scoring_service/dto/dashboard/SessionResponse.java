package tn.epos.scoring_service.dto.dashboard;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Représente une session d'évaluation pour l'évaluateur connecté.
 *
 * Correspond EXACTEMENT aux champs attendus par SessionModel.fromJson()
 * dans Flutter (session_model.dart) :
 *
 *   json['id']          → int
 *   json['stationNom']  → String  (ex: "Station 3 — Titrimétrie")
 *   json['matiere']     → String  (ex: "Chimie Thérapeutique")
 *   json['annee']       → String  (ex: "CT-2025")
 *   json['statut']      → String  ("EN_COURS" | "A_VENIR" | "TERMINEE")
 *   json['heureDebut']  → String  (ex: "09:42")
 *   json['heureFin']    → String? (nullable)
 *   json['nbEtudiants'] → int
 *   json['salle']       → String  (ex: "Salle B3")
 *   json['lotActuel']   → int
 *   json['totalLots']   → int
 *
 * Note : stationNom, matiere, annee, salle sont des données de l'exam-service.
 * Elles sont enrichies par EvaluateurDashboardService via ExamServiceClient.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionResponse {

    private Long   id;
    private Long   stationId;
    private String stationNom;
    private String matiere;
    private String annee;
    private String statut;       // "EN_COURS" | "A_VENIR" | "TERMINEE"
    private String heureDebut;   // format "HH:mm"
    private String heureFin;     // nullable
    private int    nbEtudiants;
    private String salle;
    private int    lotActuel;
    private int    totalLots;
}
