-- =============================================================
-- V2 — Cross-service ID-only links on rotation + notations
--
-- Closes issues #82 and #83 (Sprint 3 data-model foundation).
-- Station and GrilleEvaluation live in exam-service / exam_db, so these
-- are logical FKs only — no DB-level FK constraint, same precedent as
-- rotation.evaluateur_id (auth_db.users) and notation_items.item_id
-- (exam_db.items_evaluation) in V1.
--
-- Backfill strategy: all existing rows get station_id = 1 (and
-- grille_id = 1 for notations) — dev / test data is throwaway. Mirrors
-- the V2 exam-service backfill pattern (PR #94).
-- =============================================================

ALTER TABLE rotation ADD COLUMN station_id BIGINT;

UPDATE rotation SET station_id = 1 WHERE station_id IS NULL;

ALTER TABLE rotation ALTER COLUMN station_id SET NOT NULL;

CREATE INDEX idx_rotation_station_id ON rotation(station_id);

ALTER TABLE notations ADD COLUMN station_id BIGINT;
ALTER TABLE notations ADD COLUMN grille_id  BIGINT;

UPDATE notations
   SET station_id = 1,
       grille_id  = 1
 WHERE station_id IS NULL
    OR grille_id  IS NULL;

ALTER TABLE notations ALTER COLUMN station_id SET NOT NULL;
ALTER TABLE notations ALTER COLUMN grille_id  SET NOT NULL;

CREATE INDEX idx_notations_station_id ON notations(station_id);
CREATE INDEX idx_notations_grille_id  ON notations(grille_id);
