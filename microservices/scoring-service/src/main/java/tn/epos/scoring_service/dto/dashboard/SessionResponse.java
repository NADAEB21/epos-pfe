package tn.epos.scoring_service.dto.dashboard;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

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
 * ADR-0012 (additif, anciens clients ignorent) :
 *   json['debutPrevu']           → String? "yyyy-MM-dd HH:mm:ss" — instant
 *      absolu prédit du début du passage, déjà réconcilié serveur-side (ancré
 *      launched_at, décalé du temps de pause cumulé + pause en cours). L'app
 *      mobile fait : secondsUntilNext = debutPrevu − (horloge appareil + offset).
 *   json['avertissementLeadSec'] → int — seuil (s) du préavis, échoé de la config.
 *   json['enPause']              → bool — l'examen est en pause ; le mobile gèle
 *      son compte à rebours tant que c'est vrai.
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
    private Long lotId;        // pour le suivi WebSocket du lot et les stats
    private int  groupeNumero; // numéro du groupe au sein du lot (1..K)
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

    // ── ADR-0012 : compte à rebours + avertissement de transition ────────────
    // Instant absolu (date+heure) du début prévu du passage, réconcilié une
    // seule fois côté serveur (launched_at + temps de pause). Le mobile compte
    // à rebours dessus ; full timestamp car la pause/le multi-jours peut le
    // déplacer hors du jour courant.
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime debutPrevu;

    // Seuil de préavis (secondes) issu de la config examen ; 0 = pas d'avertissement.
    private int avertissementLeadSec;

    // L'examen est-il actuellement en pause — le mobile gèle son compte à rebours.
    private boolean enPause;
}
