package tn.epos.auth_service.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * DTO retourné par GET /api/v1/auth/me.
 *
 * Structure :
 *   json['id']     → int
 *   json['email']  → String
 *   json['nom']    → String
 *   json['prenom'] → String
 *   json['role']   → String  (rôle principal — voir ci-dessous)
 *   json['roles']  → [ { role, matiereId }, ... ]  (TOUS les rôles)
 *
 * Un utilisateur peut cumuler plusieurs rôles : un RESPONSABLE_MATIERE est
 * fréquemment aussi EVALUATEUR sur les examens d'un collègue. Les clients
 * doivent raisonner sur "roles", pas sur "role".
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MeResponse {

    private Long   id;
    private String email;
    private String nom;
    private String prenom;

    /**
     * Rôle principal, choisi de façon déterministe par ordre de privilège
     * décroissant : SUPER_ADMIN > RESPONSABLE_MATIERE > EVALUATEUR.
     *
     * Conservé pour compatibilité avec les clients existants. Il ne décrit PAS
     * l'utilisateur à lui seul dès qu'il cumule des rôles — utiliser {@link #roles}.
     */
    private String role;

    /** Tous les rôles de l'utilisateur, avec leur matiereId (null si global). */
    private List<RoleAssignmentDto> roles;
}
