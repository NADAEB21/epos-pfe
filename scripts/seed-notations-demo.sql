-- Demo notation seed for the Résultats screen (issue #90 / lifecycle end-stage).
--
-- The mobile évaluateur app that writes scores is unbuilt, so the `notations`
-- table is empty in dev and the post-TERMINE Résultats screen has nothing to
-- show. This seeds one locked notation per rotation_assignment of a TERMINE exam
-- so the lifecycle's end stage is demonstrable.
--
-- Idempotent: skips assignments that already carry a notation (the
-- assignment_id UNIQUE constraint would otherwise 23505). Re-runnable.
--
-- Target: exam 16 (TP Chimie Thérapeutique, TERMINE) — 4 stations, 56 assignments.
-- Run against scoring_db:
--   docker exec -i epos-postgres psql -U admin -d scoring_db < scripts/seed-notations-demo.sql
--
-- station_id -> grille_id is hardcoded because grilles live in exam_db (no
-- cross-DB join). Verify the mapping if exam 16's stations/grilles are re-seeded:
--   exam_db:  SELECT s.id station, g.id grille FROM stations s
--             JOIN grilles_evaluation g ON g.station_id = s.id WHERE s.examen_id = 16;

INSERT INTO notations (score_final, timestamp, temps_additionnel, is_synced, verouillee, assignment_id, station_id, grille_id)
SELECT
    -- Deterministic, varied score in [8, 19] out of the grille's noteMax (20),
    -- so the demo shows a believable spread (a few fails, mostly passes).
    8 + ((ra.id * 7 + r.station_id) % 12)        AS score_final,
    NOW()                                         AS timestamp,
    0                                             AS temps_additionnel,
    true                                          AS is_synced,
    true                                          AS verouillee,
    ra.id                                         AS assignment_id,
    r.station_id                                  AS station_id,
    CASE r.station_id
        WHEN 26 THEN 15
        WHEN 27 THEN 16
        WHEN 28 THEN 17
        WHEN 29 THEN 18
    END                                           AS grille_id
FROM rotation_assignment ra
JOIN examen_participations p ON ra.participation_id = p.id
JOIN rotation r              ON ra.rotation_id     = r.id
WHERE p.examen_id = 16
  AND r.station_id IN (26, 27, 28, 29)
  AND NOT EXISTS (SELECT 1 FROM notations n WHERE n.assignment_id = ra.id);
