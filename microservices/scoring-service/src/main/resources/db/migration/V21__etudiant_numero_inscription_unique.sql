-- #351 — assainissement de l'annuaire (doublons hérités du seed, ids 1–72 en
-- jusqu'à 4 exemplaires) PUIS pose de la contrainte que V3 avait différée
-- (V3__participation_unique_examen_etudiant.sql — la table portait déjà des
-- triplons à l'époque). Idempotent : rejouable sans effet sur une base propre.

-- 1. Normalisation des données existantes (SQL Standard)
UPDATE etudiants SET numero_inscription = UPPER(TRIM(numero_inscription));
UPDATE etudiants SET numero_inscription = NULL WHERE numero_inscription = '';

-- 2. Purge sécurisée des doublons SANS inscription (id 1-72 mentionnés)
-- On supprime la fiche si un autre étudiant a le même numéro ET un ID plus petit,
-- MAIS seulement si la fiche à supprimer n'est liée à aucune participation.
DELETE FROM etudiants
WHERE id IN (
    SELECT e1.id
    FROM etudiants e1
    WHERE EXISTS (
        SELECT 1 FROM etudiants e2
        WHERE e2.numero_inscription = e1.numero_inscription
          AND e2.id < e1.id
    )
      AND NOT EXISTS (
        SELECT 1 FROM examen_participations p
        WHERE p.etudiant_id = e1.id
    )
);

-- 3. Preuve AVANT contrainte (critère d'acceptation du ticket — à vérifier en
--    revue, doit renvoyer 0 ligne juste avant l'ALTER TABLE ci-dessous) :
--    SELECT numero_inscription, COUNT(*) FROM etudiants
--    GROUP BY 1 HAVING COUNT(*) > 1;

-- 4. La contrainte que V3 avait différée. NULL reste autorisé plusieurs fois
--    (sémantique standard Postgres pour UNIQUE) — aucun étudiant existant n'a
--    numero_inscription NULL en pratique, mais rien ne l'interdit au modèle.
--    Ajout de la contrainte d'unicité (Syntaxe standard)
ALTER TABLE etudiants ADD CONSTRAINT uq_etudiant_numero_inscription UNIQUE (numero_inscription);