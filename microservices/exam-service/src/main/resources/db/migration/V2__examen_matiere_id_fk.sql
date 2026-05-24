-- =============================================================
-- V2 — Examen.matiere String → matiere_id Long (cross-service FK)
--
-- Closes issue #81 (data foundation).
-- matieres table lives in auth_db (see infrastructure/init-db/init.sql).
-- No DB-level FK because matieres is in a different database — logical
-- FK only, same precedent as station_evaluateurs.evaluateur_id.
--
-- Backfill strategy: all existing rows get matiere_id = 1 (placeholder,
-- 'Chimie thérapeutique'). Dev / test data is throwaway; production
-- starts from init.sql.
-- =============================================================

ALTER TABLE examens ADD COLUMN matiere_id BIGINT;

UPDATE examens SET matiere_id = 1 WHERE matiere_id IS NULL;

ALTER TABLE examens ALTER COLUMN matiere_id SET NOT NULL;

ALTER TABLE examens DROP COLUMN matiere;

CREATE INDEX idx_examens_matiere_id ON examens(matiere_id);
