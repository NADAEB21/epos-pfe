package tn.epos.common.security.revocation;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * #306 — authentifie les appels inter-services vers les endpoints {@code /internal/**}
 * d'auth-service, sans introduire de nouveau secret à déployer.
 *
 * <p>Le poller de révocation n'a pas de jeton utilisateur (il n'agit pour personne), et créer un
 * « compte de service » ou un secret supplémentaire serait une chose de plus à provisionner sur
 * le PC de la faculté. Les services partagent déjà {@code JWT_SECRET} : on en DÉRIVE la preuve —
 * HMAC-SHA256 du libellé d'usage — au lieu d'exposer le secret lui-même dans un en-tête.
 *
 * <p>Défense en profondeur, pas première ligne : {@code /internal/**} n'est de toute façon PAS
 * routé par la gateway (allowlist de chemins) et aucun service n'est exposé sur l'hôte — cet
 * en-tête compte le jour où l'un de ces deux faits cesse d'être vrai.
 */
public final class InternalCallAuthenticator {

    public static final String HEADER = "X-Epos-Internal";

    /** Libellé d'usage : changer d'usage = changer de preuve, même secret. */
    private static final String PURPOSE = "epos-internal-revocations";

    private InternalCallAuthenticator() {
        // utility class
    }

    /** La valeur d'en-tête attendue pour {@code jwtSecret} — hex de HMAC-SHA256(secret, usage). */
    public static String headerValue(String jwtSecret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(jwtSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(PURPOSE.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                   .append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            // HmacSHA256 est obligatoire sur toute JVM ; une clé UTF-8 non vide est valide.
            throw new IllegalStateException("HMAC-SHA256 indisponible — JVM non conforme", e);
        }
    }

    /** Comparaison en temps constant — un equals() ordinaire fuit la longueur du préfixe commun. */
    public static boolean matches(String jwtSecret, String presented) {
        if (presented == null) {
            return false;
        }
        return MessageDigest.isEqual(
                headerValue(jwtSecret).getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }
}
