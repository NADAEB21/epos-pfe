package tn.epos.scoring_service.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import tn.epos.scoring_service.entities.Rotation;
import tn.epos.scoring_service.entities.RotationStatus;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface IRotationRepository extends JpaRepository<Rotation, Long> {

    // Trouver les rotations d'un groupe spécifique
    List<Rotation> findByStudentGroupId(Long groupId);

    // Trouver les rotations associées à une station (cross-service)
    List<Rotation> findByStationId(Long stationId);

    // Trouver toutes les rotations par ordre de passage
    List<Rotation> findAllByOrderByOrdrePassageAsc();

     /**
     * Récupère toutes les rotations d'un évaluateur.
     * Utilisé pour construire le planning du jour.
     */
     List<Rotation> findByEvaluateurId(Long evaluateurId);

    /**
     * Retourne la rotation la plus récente pour un évaluateur et une station.
     * findFirst évite NonUniqueResultException si des doublons existent.
     */
    Optional<Rotation> findFirstByEvaluateurIdAndStationIdOrderByIdDesc(
            Long evaluateurId, Long stationId);

     /**
      * Trouve la rotation d'un évaluateur pour une station donnée.
      * Utilisé lors de la création automatique d'un RotationAssignment.
      */
     Optional<Rotation> findByEvaluateurIdAndStationId(Long evaluateurId, Long stationId);

    /**
     * Nombre de rotations déjà générées pour un lot (issue #188).
     *
     * <p>Le front doit savoir, AU CHARGEMENT, si un lot possède déjà un planning : sans
     * cela il affiche « Générer » sur un lot déjà généré et la régénération — destructrice —
     * part sans confirmation. L'état de session ne suffit pas : il est vide après un reload.
     */
    long countByStudentGroupLotId(Long lotId);

    // #189 — étape 1 : quels examens cet évaluateur a-t-il des rotations dedans ?
    // Requête légère (juste les IDs), qui sert à interroger exam-service AVANT de
    // charger les rotations complètes.
    @Query("SELECT DISTINCT r.studentGroup.lot.examenId FROM Rotation r " +
            "WHERE r.evaluateurId = :evaluateurId " +
            "AND r.studentGroup IS NOT NULL AND r.studentGroup.lot IS NOT NULL")
    List<Long> findDistinctExamenIdsByEvaluateurId(@Param("evaluateurId") Long evaluateurId);

    // #189 — étape 2 : filtre au niveau requête (pas en Java stream), pour rester
    // bon marché quand le volume de rotations grandit.
    List<Rotation> findByEvaluateurIdAndStudentGroup_Lot_ExamenIdIn(
            Long evaluateurId, Collection<Long> examenIds);

    /**
     * Prochaine rotation planifiée pour cet évaluateur après la rotation courante.
     * L'évaluateur est fixe sur UNE SEULE station par examen (contrainte métier),
     * donc "chronologiquement suivant" suffit à trouver le bon prochain groupe,
     * sans avoir besoin de refiltrer par station.
     */
    Optional<Rotation> findFirstByEvaluateurIdAndDebutCreneauAfterOrderByDebutCreneauAsc(
            Long evaluateurId, LocalDateTime after);

    /**
     * Nombre de rotations d'un lot qui ne sont PAS encore TERMINE. Sert à détecter
     * automatiquement la fin d'un lot : dès que tous les groupes ont terminé
     * toutes les stations, ce compte tombe à 0 et le lot peut être clôturé.
     */
    long countByStudentGroup_Lot_IdAndStatutNot(Long lotId, RotationStatus statut);

    /**
     * #207 — rotation suivante d'une station donnée, au sens du RANG DE PASSAGE.
     *
     * <p>Volontairement séquencée sur {@code ordrePassage} et non sur {@code debutCreneau} :
     * l'horloge ne doit plus déterminer quel groupe est en cours (ADR-0014). Le voisin
     * chronologique existant ({@code findFirstByEvaluateurIdAndDebutCreneauAfter…}) répond à
     * une autre question — « quoi ensuite dans le planning » — et reste pour l'affichage.
     */
    Optional<Rotation> findFirstByStationIdAndStudentGroup_Lot_IdAndOrdrePassageGreaterThanOrderByOrdrePassageAsc(
            Long stationId, Long lotId, Integer ordrePassage);

    /**
     * #207 / ADR-0014-B — rotations du PREMIER rang d'un lot, c'est-à-dire la vague
     * qui s'ouvre quand le responsable ouvre le lot. Le carré latin place les K groupes
     * sur K stations distinctes à {@code t=0}, donc ce rang contient exactement une
     * rotation par station : ouvrir le lot = passer ces rotations-là EN_COURS.
     *
     * <p>Séquencé sur {@code ordrePassage}, jamais sur {@code debutCreneau} : l'ouverture
     * est un acte humain (ADR-0014-B §1), pas un créneau qui arrive à échéance.
     */
    List<Rotation> findByStudentGroup_Lot_IdAndOrdrePassage(Long lotId, Integer ordrePassage);

    /**
     * #208 — toutes les rotations d'un lot, tous rangs et toutes stations confondus.
     * Base du tableau de progression du responsable : la vague entière en une requête,
     * dont on dérive « quel groupe passe où » et « combien d'étudiants sont notés ».
     */
    List<Rotation> findByStudentGroup_Lot_Id(Long lotId);
}