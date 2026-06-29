package tn.epos.exam_service.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import tn.epos.exam_service.enums.StatutExamen;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

@Data
public class ExamenResponse {
    private Long id;
    private String nom;
    private Long matiereId;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate dateExamen;

    @JsonFormat(pattern = "HH:mm")
    private LocalTime heureDebut;

    private Integer dureeStationMin;
    private Integer nbEtudiantsParStation;

    // Tampon de transition inter-créneau (minutes) et délai d'avertissement
    // avant le prochain passage (secondes) — ADR-0012. Tous deux 0 par défaut.
    // scoring-service les relit : tempsBattementMin reformate le planning,
    // avertissementLeadSec est renvoyé tel quel à l'app évaluateur.
    private Integer tempsBattementMin;
    private Integer avertissementLeadSec;

    private StatutExamen statut;
    private String description;
    private boolean hasPdfSujet;
    private String pdfSujetNom;

    // Pause/reprise (ADR-0009). enPause : examen actuellement en pause (reste
    // EN_COURS). pausedAt : début de la pause en cours (null sinon).
    // totalPauseSec : secondes de pause cumulées — le Suivi calcule le temps
    // effectif côté client à partir de ces champs.
    private boolean enPause;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime pausedAt;

    private Integer totalPauseSec;

    // Instant de lancement réel (ADR-0010), null tant que l'examen n'est pas lancé
    // ou pour les lignes pré-ADR. Le Suivi ancre son horloge dessus quand présent,
    // sinon il retombe sur dateExamen + heureDebut (départ planifié).
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime launchedAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updatedAt;

    // Stations incluses dans la réponse détaillée
    private List<StationResponse> stations;
}
