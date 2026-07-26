package tn.epos.scoring_service.dto.dashboard;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * #208 / #252 — ce que le responsable a besoin de voir pendant l'épreuve, et <b>rien de plus</b>.
 *
 * <p><b>Le cahier des charges, dans les mots de Nada (2026-07-21) :</b> « le seul détail dont le
 * responsable a besoin sur une station, c'est <i>quel groupe est en train d'être noté</i> et une
 * stat du type <i>2/4 notés</i> […] donc pas de « dépassement », pas d'horloge qui tourne côté
 * responsable pour chaque station, seulement un chronomètre pour l'examen. »
 *
 * <p><b>Tout est DÉRIVÉ CÔTÉ SERVEUR, à partir de l'état stocké.</b> Le client ne recalcule rien :
 * c'est précisément en laissant le web re-déduire un statut à partir de l'horloge que le tableau
 * affichait « dépassement / encore en cours » sur des rotations pourtant `TERMINE` en base. Le
 * mot d'ordre d'ADR-0014 vaut aussi pour l'affichage : on lit l'état, on ne le devine pas.
 *
 * <p><b>Aucune notion de dépassement.</b> {@code ouvertA} est une ancre de mesure, informative.
 * Rien ne doit en être dérivé — ni statut, ni visibilité, ni action bloquée : un état « dépassé »
 * reconstruirait le PLAFOND qu'ADR-0014 retire, en habits d'affichage.
 */
@Getter
@Builder
public class SuiviProgressionResponse {

    private Long examenId;

    /**
     * La vague actuellement ouverte, ou {@code null} si aucune ne l'est (avant la première
     * ouverture, ou entre deux vagues — cas parfaitement normal sous ADR-0014-B, où c'est le
     * responsable qui ouvre la suivante).
     */
    private LotEnCours lotOuvert;

    /**
     * #208 — <b>l'alerte « lot terminé »</b>. Vraie quand une vague est ouverte et que TOUTES ses
     * rotations sont TERMINE : tous les évaluateurs ont validé leur dernier groupe, la salle peut
     * tourner. C'est la moitié responsable de la poignée de main d'ADR-0014-B.
     *
     * <p>Dérivée, jamais stockée — et volontairement distincte de {@code Lot.statut}, qui signifie
     * « présence prise » et vaut donc EN_COURS bien avant que la vague soit finie.
     */
    private boolean lotTermine;

    /**
     * Le lot que « Lot suivant » ouvrira : la plus petite vague non encore démarrée de l'examen.
     * {@code null} quand il n'en reste aucune — le bouton disparaît alors, au lieu d'échouer au clic.
     */
    private LotSuivant lotSuivant;

    /** Une ligne par station du circuit, dans l'ordre du circuit. */
    private List<StationProgression> stations;

    @Getter
    @Builder
    public static class LotEnCours {
        private Long          id;
        private Integer       numeroLot;

        /**
         * #252 — instant d'ouverture RÉEL de cette vague, seule ancre honnête du chronomètre.
         * Le bandeau mesurait auparavant depuis {@code examens.launched_at}, qui ne connaît que la
         * première vague : l'examen basculait en « dépassement » dès la fin du lot 1 et y restait
         * (+42:16 et croissant, mesuré sur l'examen 31), alors que rien d'anormal ne se passait.
         *
         * <p>{@code null} pour une vague ouverte avant la migration V9 : on affiche alors « — »
         * plutôt qu'un chiffre faux (aucun rattrapage inventé).
         */
        private LocalDateTime ouvertA;

        /**
         * #252 — temps écoulé depuis l'ouverture, en secondes, <b>calculé par le SERVEUR</b>.
         * {@code null} si la vague n'a pas d'horodatage (ouverte avant V9) : le client affiche
         * « — », il n'invente rien.
         *
         * <p><b>Pourquoi ce n'est pas au client de faire la soustraction.</b> Les horloges ne
         * coïncident pas : le conteneur tourne en CEST (UTC+2), le bean {@code Clock} est épinglé
         * Africa/Tunis (UTC+1, ADR-0010), et le navigateur suit l'heure du poste. Un
         * {@code Date.now() - ouvertA} dans le front afficherait donc <b>+1:00:00 dès l'ouverture
         * d'une vague</b> — exactement le décalage documenté par ADR-0010. En envoyant une DURÉE
         * plutôt qu'un instant, la mesure devient insensible au fuseau du poste, et c'est aussi
         * la doctrine : le serveur dérive, le client affiche.
         *
         * <p>⚠️ <b>Ne compte QUE tant que la vague tourne réellement.</b> Une fois toutes ses
         * rotations TERMINE, ce champ repasse à {@code null} : sinon il continuerait de grimper
         * pendant l'attente entre deux vagues et reproduirait très exactement le « +42:16 et
         * croissant » que ce ticket supprime — le PLAFOND, réinventé sous un autre nom. Quand la
         * vague est finie, le responsable n'a pas besoin d'un chronomètre : il a besoin de
         * l'alerte « lot terminé » et du bouton « Lot suivant ».
         *
         * <p>Aucun seuil, aucune comparaison à une durée prévue, aucun état « dépassé » n'est
         * dérivé de ce nombre. Il monte, on l'affiche, c'est tout.
         */
        private Long          ecouleSec;

        /** Groupes de la vague déjà bouclés sur TOUTES les stations, sur le total. */
        private int           groupesTermines;
        private int           groupesTotal;
    }

    @Getter
    @Builder
    public static class LotSuivant {
        private Long    id;
        private Integer numeroLot;
        /** Faux tant que ses rotations n'ont pas été générées : le bouton doit le dire, pas planter. */
        private boolean rotationsGenerees;
    }

    @Getter
    @Builder
    public static class StationProgression {
        private Long   stationId;
        private Long   evaluateurId;

        /**
         * Le groupe en cours de notation à cette station, {@code null} si la station a fini sa
         * vague (ou ne l'a pas commencée). C'est le « quel groupe est en train d'être noté ».
         */
        private Integer groupeEnCours;
        private Integer rangEnCours;

        /** « 2/4 notés » : étudiants notés à cette station sur l'effectif de la vague. */
        private int     etudiantsNotes;
        private int     etudiantsTotal;

        /**
         * Progression de la station dans la vague, en groupes. Remplace le compte à rebours :
         * ce que le responsable veut savoir, c'est l'avancement, pas le temps restant.
         */
        private int     groupesTermines;
        private int     groupesTotal;

        /**
         * {@code EN_ATTENTE} | {@code EN_COURS} | {@code TERMINE} — <b>lu</b> sur les rotations,
         * jamais calculé à partir de l'heure. Pas de « dépassement » : ce n'était pas un état
         * mais une opinion de l'horloge sur un travail qu'elle ne voyait pas.
         */
        private String  statut;
    }
}
