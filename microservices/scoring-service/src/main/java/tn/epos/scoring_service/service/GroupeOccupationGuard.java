package tn.epos.scoring_service.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tn.epos.common.exception.BusinessException;
import tn.epos.scoring_service.entities.Rotation;
import tn.epos.scoring_service.entities.RotationStatus;
import tn.epos.scoring_service.repositories.IRotationRepository;

import java.util.Objects;
import java.util.Optional;

/**
 * #431 — un groupe d'étudiants n'est physiquement qu'à UNE station à la fois.
 *
 * <p>Le carré latin ({@code RotationGenerationService}) place bien les K groupes sur K
 * stations distinctes à chaque rang — mais cette garantie ne vaut que tant que toutes les
 * stations sont sur le MÊME rang. Depuis #209 chaque évaluateur avance seul ; la seule
 * garde d'ordre était « même station » (#423). Une station rapide qui passe au rang 2
 * pendant qu'une station lente note encore le rang 1 ouvre donc le groupe suivant à deux
 * stations en même temps : l'une avec les étudiants, l'autre avec un minuteur qui tourne
 * sur des absents — et la possibilité de les noter (mauvais clic, ou évaluateur pressé).
 *
 * <p>Cette classe est la source unique de la question « ce groupe est-il encore ouvert
 * ailleurs ? », posée par les deux ouvertures hors rang initial : « Groupe suivant »
 * ({@code EvaluateurDashboardService.avancerGroupe}) et la reprise par un remplaçant
 * ({@code EvaluateurSubstitutionService}). Le rang 1 ({@code LotOuvertureService}) ouvre
 * K groupes à K stations distinctes : il n'est pas concerné.
 *
 * <p>Le refus est une {@code BusinessException} (400) nominative : le mobile en affiche le
 * message tel quel (#297) sans démonter la grille (#248). On préfère un refus expliqué à
 * un bouton grisé sans raison.
 */
@Component
@RequiredArgsConstructor
public class GroupeOccupationGuard {

    private final IRotationRepository rotationRepository;

    /**
     * La rotation {@code EN_COURS} du même groupe à une AUTRE station, s'il y en a une.
     * Vide si la cible n'a pas de groupe (donnée antérieure à la génération par lot).
     */
    public Optional<Rotation> enCoursAilleurs(Rotation cible) {
        if (cible.getStudentGroup() == null || cible.getStudentGroup().getId() == null) {
            return Optional.empty();
        }
        return rotationRepository.findByStudentGroupId(cible.getStudentGroup().getId()).stream()
                .filter(r -> r.getStatut() == RotationStatus.EN_COURS)
                .filter(r -> !Objects.equals(r.getId(), cible.getId()))
                .filter(r -> !Objects.equals(r.getStationId(), cible.getStationId()))
                .findFirst();
    }

    /**
     * Refuse d'ouvrir {@code cible} tant que son groupe est en cours d'évaluation à une
     * autre station. Le message nomme le groupe : c'est ce que l'évaluateur voit dans la
     * salle (« le groupe 2 »), pas un identifiant de rotation.
     */
    public void refuserSiOccupeAilleurs(Rotation cible) {
        enCoursAilleurs(cible).ifPresent(autre -> {
            Integer numero = cible.getStudentGroup().getNumeroGroupe();
            String groupe = numero != null ? "Le groupe " + numero : "Ce groupe";
            throw new BusinessException(groupe + " est encore en cours d'évaluation à une autre "
                    + "station. Attendez que son évaluateur l'ait validé avant de le recevoir.");
        });
    }
}
