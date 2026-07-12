package tn.epos.auth_service.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * DTO retourné par GET /api/v1/auth/me.
 *
 * Structure attendue par UserModel.fromJson() dans Flutter :
 *   json['id']     → int
 *   json['email']  → String
 *   json['nom']    → String
 *   json['prenom'] → String
 *   json['role']   → String ("EVALUATEUR" | "RESPONSABLE_MATIERE" | "SUPER_ADMIN")
 *
 * Décalage B corrigé : le champ "role" utilise exactement les valeurs
 * de RoleType.java pour que le mapping Flutter soit correct.
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
     * Rôle principal de l'utilisateur.
     * Valeurs possibles : "EVALUATEUR", "RESPONSABLE_MATIERE", "SUPER_ADMIN"
     * L'app mobile ne concerne que les EVALUATEURS.
     */
    private String role;
}
