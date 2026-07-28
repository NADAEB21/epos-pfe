package tn.epos.scoring_service.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tn.epos.scoring_service.client.ExamGenerationView;
import tn.epos.scoring_service.client.ExamServiceClient;
import tn.epos.scoring_service.dto.ConvocationDTO;
import tn.epos.scoring_service.dto.EnvoiConvocationsResult;
import tn.epos.scoring_service.entities.Etudiant;
import tn.epos.scoring_service.entities.ExamenParticipation;
import tn.epos.scoring_service.entities.Lot;
import tn.epos.scoring_service.repositories.IExamenParticipationRepository;
import tn.epos.scoring_service.service.email.ConvocationEmailService;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * #227 — construit les convocations d'un examen et les envoie.
 *
 * <p><b>Pourquoi la dérivation vit ICI.</b> L'heure de convocation était
 * calculée dans le composant Angular. Dès lors que le backend doit l'écrire
 * dans un e-mail, il lui faut la même règle : deux implémentations d'une même
 * règle métier, dans deux langages, dérivent tôt ou tard — et le jour où elles
 * divergent, l'écran et l'e-mail de l'étudiant se contredisent. Le web lit donc
 * cette dérivation au lieu de la refaire.
 *
 * <p><b>La règle.</b> Un lot est une vague qui enchaîne le circuit complet
 * derrière les vagues du MÊME jour :
 * {@code heureDebut + (rang du lot dans sa journée) × nbStations × dureeStationMin}.
 * Les arrivées repartent de {@code heureDebut} chaque jour (#147) : un lot placé
 * au jour 2 ne fait pas la queue derrière le jour 1.
 */
@Slf4j
@Service
public class ConvocationService {

    /** Défauts du backend (RotationGenerationService) quand l'examen ne les fixe pas. */
    private static final LocalTime DEFAUT_HEURE_DEBUT = LocalTime.of(9, 0);
    private static final int DEFAUT_DUREE_STATION_MIN = 15;

    private final IExamenParticipationRepository participationRepository;
    private final ExamServiceClient examServiceClient;
    private final ConvocationEmailService emailService;
    private final Clock clock;

    public ConvocationService(IExamenParticipationRepository participationRepository,
                              ExamServiceClient examServiceClient,
                              ConvocationEmailService emailService,
                              Clock clock) {
        this.participationRepository = participationRepository;
        this.examServiceClient = examServiceClient;
        this.emailService = emailService;
        this.clock = clock;
    }

    /**
     * Les convocations de l'examen, dans l'ordre du listing (#256 : lot, puis
     * position dans le fichier importé — jamais l'alphabet). Les étudiants non
     * encore répartis en lots sont exclus : sans vague, il n'y a pas d'heure à
     * annoncer, et inventer une convocation serait pire que de n'en pas donner.
     */
    public List<ConvocationDTO> construire(Long examenId) {
        ExamGenerationView exam = examServiceClient.getExamForGeneration(examenId);
        List<ExamenParticipation> participations = participationRepository.findByExamenId(examenId);

        int nbStations = exam.stations() == null ? 0 : exam.stations().size();
        int dureeStation = exam.dureeStationMin() == null
                ? DEFAUT_DUREE_STATION_MIN : exam.dureeStationMin();
        LocalTime heureDebut = exam.heureDebut() == null ? DEFAUT_HEURE_DEBUT : exam.heureDebut();

        Map<Long, Integer> rangParLot = calculerRangsParJour(participations, exam.dateExamen());

        List<ConvocationDTO> out = new ArrayList<>();
        for (ExamenParticipation p : participations) {
            Lot lot = p.getLot();
            if (lot == null || lot.getNumeroLot() == null) {
                continue; // pas encore réparti → pas de vague → pas de convocation
            }
            int rang = rangParLot.getOrDefault(lot.getId(), 0);
            LocalTime heure = heureDebut.plusMinutes((long) rang * nbStations * dureeStation);
            out.add(versConvocation(p, lot, heure, exam.dateExamen()));
        }

        out.sort(Comparator
                .comparing(ConvocationDTO::lotNumero, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(ConvocationDTO::ordre_import,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(c -> c.nom() == null ? "" : c.nom())
                .thenComparing(c -> c.prenom() == null ? "" : c.prenom()));
        return out;
    }

    /** Projette une participation + sa vague en convocation. */
    private ConvocationDTO versConvocation(ExamenParticipation p, Lot lot, LocalTime heure,
                                           LocalDate dateExamen) {
        Etudiant e = p.getEtudiant();
        return new ConvocationDTO(
                p.getId(),
                e == null ? null : e.getId(),
                e == null ? null : e.getNom(),
                e == null ? null : e.getPrenom(),
                e == null ? null : e.getNumero_inscription(),
                e == null ? null : e.getEmail(),
                p.getOrdre_import(),
                lot.getId(),
                lot.getNumeroLot(),
                jourDuLot(lot, dateExamen),
                String.format("%02d:%02d", heure.getHour(), heure.getMinute()),
                p.getConvocation_envoyee_a());
    }

    /**
     * Rang de chaque lot DANS SA JOURNÉE (#147). Les lots sont classés par
     * numéro, puis chaque journée recommence à 0 — sinon un lot du jour 2
     * hériterait de l'attente cumulée du jour 1 et son heure serait absurde.
     */
    private Map<Long, Integer> calculerRangsParJour(List<ExamenParticipation> participations,
                                                    LocalDate dateExamen) {
        Map<Long, Lot> lots = new HashMap<>();
        for (ExamenParticipation p : participations) {
            Lot l = p.getLot();
            if (l != null && l.getNumeroLot() != null) {
                lots.put(l.getId(), l);
            }
        }
        List<Lot> ordonnes = new ArrayList<>(lots.values());
        ordonnes.sort(Comparator.comparing(Lot::getNumeroLot));

        Map<LocalDate, Integer> prochainRang = new HashMap<>();
        Map<Long, Integer> rangParLot = new HashMap<>();
        for (Lot l : ordonnes) {
            LocalDate jour = jourDuLot(l, dateExamen);
            int rang = prochainRang.getOrDefault(jour, 0);
            prochainRang.put(jour, rang + 1);
            rangParLot.put(l.getId(), rang);
        }
        return rangParLot;
    }

    private LocalDate jourDuLot(Lot lot, LocalDate dateExamen) {
        return lot.getJour() != null ? lot.getJour() : dateExamen;
    }

    /**
     * Envoie la convocation à chaque étudiant JOIGNABLE et note la date d'envoi.
     *
     * <p>Un destinataire en échec n'interrompt jamais les autres : chaque envoi
     * est isolé et produit sa propre ligne de bilan. Les étudiants sans adresse
     * ne sont pas une erreur — ils sont comptés à part, parce que ce sont
     * exactement ceux que le responsable devra convoquer en main propre.
     */
    public EnvoiConvocationsResult envoyer(Long examenId) {
        ExamGenerationView exam = examServiceClient.getExamForGeneration(examenId);
        String examenNom = exam.nom() == null ? "Examen" : exam.nom();
        List<ConvocationDTO> convocations = construire(examenId);

        List<EnvoiConvocationsResult.EnvoiLigne> lignes = new ArrayList<>();
        List<ExamenParticipation> aMarquer = new ArrayList<>();
        Map<Long, ExamenParticipation> parId = new HashMap<>();
        for (ExamenParticipation p : participationRepository.findByExamenId(examenId)) {
            parId.put(p.getId(), p);
        }

        int envoyes = 0;
        int sansAdresse = 0;
        int echecs = 0;

        for (ConvocationDTO c : convocations) {
            if (!c.joignable()) {
                sansAdresse++;
                lignes.add(new EnvoiConvocationsResult.EnvoiLigne(
                        c.participationId(), c.nom(), c.prenom(), null, "SANS_ADRESSE",
                        "Pas d'adresse e-mail — convocation à remettre en main propre."));
                continue;
            }
            try {
                emailService.envoyerConvocation(c, examenNom);
                envoyes++;
                ExamenParticipation p = parId.get(c.participationId());
                if (p != null) {
                    p.setConvocation_envoyee_a(java.time.LocalDateTime.now(clock));
                    aMarquer.add(p);
                }
                lignes.add(new EnvoiConvocationsResult.EnvoiLigne(
                        c.participationId(), c.nom(), c.prenom(), c.email(), "ENVOYE", null));
            } catch (RuntimeException ex) {
                echecs++;
                log.warn("Convocation non envoyée à {} (examen {}) : {}",
                        c.email(), examenId, ex.getMessage());
                lignes.add(new EnvoiConvocationsResult.EnvoiLigne(
                        c.participationId(), c.nom(), c.prenom(), c.email(), "ECHEC",
                        "Échec de l'envoi : " + ex.getMessage()));
            }
        }

        if (!aMarquer.isEmpty()) {
            participationRepository.saveAll(aMarquer);
        }
        return new EnvoiConvocationsResult(convocations.size(), envoyes, sansAdresse, echecs,
                emailService.estSimule(), lignes);
    }
}
