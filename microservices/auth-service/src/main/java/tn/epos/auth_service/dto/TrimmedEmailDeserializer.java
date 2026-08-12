package tn.epos.auth_service.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;

/**
 * Retire les espaces autour d'une adresse e-mail reçue, AVANT la validation.
 *
 * <p><b>Le défaut d'ergonomie que ça corrige.</b> Une adresse collée depuis un tableur, un
 * courriel ou un SMS traîne très souvent un espace ou une tabulation. La validation
 * {@code @Email} la refusait alors en <b>400</b> — « adresse invalide » — pour une adresse
 * parfaitement valide dont l'utilisateur ne voyait pas ce qui clochait. Sur un téléphone, avec
 * le presse-papier et la saisie prédictive, c'est le cas le plus banal du monde.
 *
 * <p><b>Pourquoi ici, et pas un trim global sur toutes les chaînes.</b> Un trim appliqué à tous
 * les champs toucherait aussi les <b>mots de passe</b> — or un espace de bord y est un caractère
 * légitime, et le supprimer en silence rendrait un mot de passe correct impossible à saisir.
 * L'ergonomie ne consiste pas à nettoyer partout : elle consiste à nettoyer là où l'espace n'a
 * aucun sens. Dans une adresse e-mail, il n'en a aucun.
 *
 * <p>Ordre des opérations, et c'est le point : Jackson désérialise <b>avant</b> que Bean Validation
 * ne s'exécute. Le trim a donc lieu avant {@code @Email} et {@code @NotBlank}. Une saisie qui ne
 * contient QUE des espaces devient une chaîne vide, donc {@code @NotBlank} la refuse avec le bon
 * message — « ne doit pas être vide » plutôt que « adresse invalide ».
 *
 * <p>La <b>casse</b> n'est pas traitée ici, volontairement : elle l'est déjà à deux niveaux plus
 * sûrs — {@code User.@PrePersist}/{@code @PreUpdate} à l'écriture et les requêtes {@code lower(...)}
 * de {@code UserRepository} à la lecture (#29, #285). La normaliser une troisième fois ici ne
 * fermerait rien de plus et disperserait la responsabilité.
 */
public class TrimmedEmailDeserializer extends JsonDeserializer<String> {

    @Override
    public String deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        String brut = p.getValueAsString();
        return brut == null ? null : brut.trim();
    }
}
