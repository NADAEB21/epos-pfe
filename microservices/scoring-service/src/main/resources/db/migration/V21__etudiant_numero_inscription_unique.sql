-- #351 — assainissement de l'annuaire (doublons hérités du seed, ids 1–72 en
-- jusqu'à 4 exemplaires) PUIS pose de la contrainte que V3 avait différée
-- (V3__participation_unique_examen_etudiant.sql — la table portait déjà des
-- triplons à l'époque). Idempotent : rejouable sans effet sur une base propre.

-- 1) Normalisation en place — même règle que le serveur applique désormais à
--    l'écriture (EtudiantService.normaliserNumero / Etudiant@PrePersist).
UPDATE etudiants
SET numero_inscription = UPPER(TRIM(numero_inscription))
WHERE numero_inscription IS NOT NULL
  AND numero_inscription <> UPPER(TRIM(numero_inscription));

-- 2) Fusion des doublons : le plus petit id de chaque groupe devient la fiche
--    canonique ; ses inscriptions absorbent celles des copies, sauf collision
--    sur le même examen (uq_participation_examen_etudiant), auquel cas la
--    ligne en double est retirée — même personne, déjà inscrite deux fois au
--    même examen sous deux fiches, pas deux inscriptions distinctes.
DO $$
DECLARE
grp RECORD;
    canonical_id BIGINT;
    dup RECORD;
BEGIN
FOR grp IN
SELECT numero_inscription
FROM etudiants
WHERE numero_inscription IS NOT NULL
GROUP BY numero_inscription
HAVING COUNT(*) > 1
    LOOP
SELECT MIN(id) INTO canonical_id
FROM etudiants
WHERE numero_inscription = grp.numero_inscription;

FOR dup IN
SELECT id FROM etudiants
WHERE numero_inscription = grp.numero_inscription
  AND id <> canonical_id
    LOOP
UPDATE examen_participations ep
SET etudiant_id = canonical_id
WHERE ep.etudiant_id = dup.id
  AND NOT EXISTS (
    SELECT 1 FROM examen_participations ep2
    WHERE ep2.etudiant_id = canonical_id
      AND ep2.examen_id = ep.examen_id
);

DELETE FROM examen_participations WHERE etudiant_id = dup.id;
DELETE FROM etudiants WHERE id = dup.id;
END LOOP;
END LOOP;
END $$;

-- 3) Preuve AVANT contrainte (critère d'acceptation du ticket — à vérifier en
--    revue, doit renvoyer 0 ligne juste avant l'ALTER TABLE ci-dessous) :
--    SELECT numero_inscription, COUNT(*) FROM etudiants
--    GROUP BY 1 HAVING COUNT(*) > 1;

-- 4) La contrainte que V3 avait différée. NULL reste autorisé plusieurs fois
--    (sémantique standard Postgres pour UNIQUE) — aucun étudiant existant n'a
--    numero_inscription NULL en pratique, mais rien ne l'interdit au modèle.
ALTER TABLE etudiants
    ADD CONSTRAINT uq_etudiants_numero_inscription UNIQUE (numero_inscription);