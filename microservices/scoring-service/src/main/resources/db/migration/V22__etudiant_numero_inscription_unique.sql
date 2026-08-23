-- #351 — assainissement de l'annuaire (doublons hérités du seed, ids 1–72 en
-- jusqu'à 4 exemplaires) PUIS pose de la contrainte que V3 avait différée
-- (V3__participation_unique_examen_etudiant.sql — la table portait déjà des
-- triplons à l'époque).
--
-- V22 et non V21 : db/vendor/postgresql/V21__ai_reader_grants.sql (PR #370)
-- occupe déjà la version 21 — les fichiers vendor partagent l'espace de
-- versions Flyway (locations db/migration + db/vendor/{vendor}), et le H2
-- des tests ne les voit pas : seule l'exécution Postgres détecte la collision.
--
-- SQL standard uniquement (H2 des tests + Postgres). Cas couverts :
--   1. copie sans aucune participation            → supprimée (étape 3) ;
--   2. copie inscrite là où le même numéro n'a    → sa participation est
--      qu'UNE inscription sur l'examen               réaffectée à la fiche
--                                                    canonique = MIN(id) (étape 2),
--                                                    notes/présence/réclamations
--                                                    suivent la ligne, rien n'est perdu ;
--   3. même numéro inscrit DEUX fois sur le MÊME  → AMBIGU : on ne devine pas
--      examen (deux fiches, deux participations,    quelle ligne porte la vérité
--      potentiellement notes/réclamations des 2)    (reclamations est en ON DELETE
--                                                    CASCADE !). Les deux fiches
--                                                    survivent et l'ALTER final
--                                                    échoue FORT : fusionner à la
--                                                    main (choisir la participation
--                                                    survivante, supprimer l'autre
--                                                    et sa fiche) puis redémarrer.

-- 1. Normalisation des données existantes (même règle que le serveur applique
--    désormais à l'écriture : EtudiantService.normaliserNumero / @PrePersist).
UPDATE etudiants SET numero_inscription = UPPER(TRIM(numero_inscription));
UPDATE etudiants SET numero_inscription = NULL WHERE numero_inscription = '';

-- 2. Fusion des inscriptions NON ambiguës : toute participation portée par une
--    copie (id non minimal de son numéro) passe à la fiche canonique MIN(id),
--    SAUF si une autre participation du même numéro existe déjà sur le même
--    examen (cas 3 ci-dessus — laissé à l'humain). La garde « aucune autre
--    participation du même numéro sur cet examen » rend l'UPDATE sûr vis-à-vis
--    de uq_participation_examen_etudiant, y compris entre deux copies.
UPDATE examen_participations
SET etudiant_id = (
    SELECT MIN(e2.id) FROM etudiants e2
    WHERE e2.numero_inscription = (
        SELECT e1.numero_inscription FROM etudiants e1
        WHERE e1.id = examen_participations.etudiant_id)
)
WHERE EXISTS (
    SELECT 1 FROM etudiants d
    WHERE d.id = examen_participations.etudiant_id
      AND d.numero_inscription IS NOT NULL
      AND EXISTS (
          SELECT 1 FROM etudiants c
          WHERE c.numero_inscription = d.numero_inscription
            AND c.id < d.id)
)
AND NOT EXISTS (
    SELECT 1
    FROM examen_participations p2
    JOIN etudiants o ON o.id = p2.etudiant_id
    JOIN etudiants d ON d.id = examen_participations.etudiant_id
    WHERE p2.id <> examen_participations.id
      AND p2.examen_id = examen_participations.examen_id
      AND o.numero_inscription = d.numero_inscription
);

-- 3. Purge des copies : après l'étape 2 elles n'ont plus de participation
--    (hors cas 3). La garde NOT EXISTS est conservée exprès : dans le cas 3,
--    la copie encore inscrite SURVIT et c'est l'ALTER ci-dessous qui échoue
--    fort — plutôt qu'une suppression qui casserait la FK ou, pire,
--    cascaderait des réclamations en silence.
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

-- 4. Preuve AVANT contrainte (critère d'acceptation du ticket — doit renvoyer
--    0 ligne juste avant l'ALTER TABLE ci-dessous) :
--    SELECT numero_inscription, COUNT(*) FROM etudiants
--    GROUP BY 1 HAVING COUNT(*) > 1;

-- 5. La contrainte que V3 avait différée. NULL reste autorisé plusieurs fois
--    (sémantique standard pour UNIQUE) — aucun étudiant existant n'a
--    numero_inscription NULL en pratique, mais rien ne l'interdit au modèle.
ALTER TABLE etudiants ADD CONSTRAINT uq_etudiant_numero_inscription UNIQUE (numero_inscription);
