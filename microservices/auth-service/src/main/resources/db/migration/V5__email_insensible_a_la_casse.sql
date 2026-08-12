-- =============================================================
-- V5 — L'e-mail cesse d'être sensible à la casse (#29 et #285)
--
-- UNE SEULE CAUSE, DEUX SYMPTÔMES
--   #29  `findByEmail` / `existsByEmail` sont des requêtes dérivées nues : se
--        connecter avec « Admin@epos.tn » ne trouve pas la ligne « admin@epos.tn ».
--        La demande de réinitialisation échoue de la même façon, et comme elle est
--        volontairement anti-énumération, elle répond 200 : la personne n'a jamais
--        son courriel et n'apprend jamais pourquoi.
--   #285 `existsByEmail` ne voit pas le doublon, donc la création passe : une faute
--        de frappe sur la casse FOURCHE l'identité d'une personne en deux comptes.
--
-- CE DÉFAUT S'EST DÉJÀ PRODUIT DANS CE DÉPÔT.
-- Constaté le 2026-08-12 dans auth_db : deux comptes pour la même adresse —
-- id 7 « s34-eval@epos.tn » (Sonia Karoui, active) et id 10 « S34-EVAL@EPOS.TN »
-- (créée le 2026-08-02 pour reproduire le défaut, déjà inactive). Le ticket n'était
-- donc pas théorique, et un index unique naïf sur lower(email) AURAIT REFUSÉ DE SE
-- CRÉER, empêchant auth-service de démarrer — y compris sur le PC de la faculté.
--
-- CE QUE FAIT CETTE MIGRATION, ET CE QU'ELLE REFUSE DE FAIRE
--   1. canonicalise en minuscules toutes les adresses SANS conflit ;
--   2. pour un conflit réel : garde la ligne de référence (l'active s'il n'y en a
--      qu'une, sinon la plus ancienne) et met les autres DE CÔTÉ — adresse préfixée,
--      compte désactivé, motif écrit. Rien n'est supprimé, rien n'est fusionné ;
--   3. crée alors l'index unique sur lower(email).
--
-- ⚠️ Elle NE FUSIONNE JAMAIS deux identités. Décider que deux comptes sont la même
-- personne est un acte humain — une migration n'a pas à le trancher. Mettre de côté
-- est réversible et tracé ; fusionner ne l'est pas. C'est la doctrine de #289 /
-- ADR-0023 (la désactivation est un départ motivé et réversible) appliquée à un
-- doublon, et non une invention pour l'occasion.
--
-- ⚠️ Elle ne fait pas non plus échouer le démarrage. Une migration qui refuse de
-- passer sur des données réelles rendrait auth-service indémarrable là où personne
-- n'a accès à la base — le remède serait pire que le défaut.
-- =============================================================

DO $$
DECLARE
    conflit RECORD;
    garde   BIGINT;
BEGIN
    -- 1. Les conflits d'abord : sinon l'UPDATE global ci-dessous violerait
    --    users_email_key en cours de route.
    FOR conflit IN
        SELECT lower(email) AS canonique, count(*) AS n
        FROM users
        GROUP BY lower(email)
        HAVING count(*) > 1
    LOOP
        -- Ligne de référence : l'unique active si elle est seule à l'être, sinon la
        -- plus ancienne. Ordre déterministe, jamais « au hasard ».
        SELECT id INTO garde
        FROM users
        WHERE lower(email) = conflit.canonique
        ORDER BY is_active DESC, created_at ASC, id ASC
        LIMIT 1;

        UPDATE users
        SET email               = 'doublon+' || id || '+' || lower(email),
            is_active           = false,
            deactivation_motif  = COALESCE(deactivation_motif,
                'Mise de cote automatique (V5) : doublon de ' || conflit.canonique
                || ' ne differant que par la casse. Aucune donnee supprimee ; un '
                || 'administrateur peut reactiver ce compte apres avoir tranche.')
        WHERE lower(email) = conflit.canonique
          AND id <> garde;

        RAISE NOTICE 'V5 — % : ligne de reference id=%, doublon(s) mis de cote.',
            conflit.canonique, garde;
    END LOOP;

    -- 2. Canonicalisation du reste : plus aucun conflit possible ici.
    UPDATE users SET email = lower(email) WHERE email <> lower(email);
END $$;

-- 3. L'index qui rend le défaut impossible, y compris sous concurrence — deux
--    créations simultanées ne peuvent plus fourcher une identité. La garde Java
--    seule laisserait cette fenêtre ouverte.
CREATE UNIQUE INDEX uq_users_email_lower ON users (lower(email));

COMMENT ON INDEX uq_users_email_lower IS
    'Unicite de l''e-mail SANS la casse (#29, #285). users_email_key (unique sur '
    'la valeur brute) est conservee : elle ne genait pas, et la retirer n''ajoute '
    'rien. C''est cet index-ci qui empeche la fourche d''identite sous concurrence.';
