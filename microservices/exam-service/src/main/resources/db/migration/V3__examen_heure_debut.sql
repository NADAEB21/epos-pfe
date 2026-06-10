-- =============================================================
-- V3 — Examen.heure_debut (start-of-day clock time for the OSCE circuit)
--
-- Needed by scoring-service rotation generation: Rotation.debutCreneau is a
-- LocalDateTime, but the exam only carried date_examen (a DATE). The circuit
-- start time is combined with date_examen to stamp each créneau.
--
-- Nullable: pre-existing exams have no start time. Generation falls back to a
-- default (09:00) when null. Existing rows are backfilled to 09:00 so legacy
-- data is coherent; new exams always carry an explicit value (request DTO
-- defaults to 09:00).
-- =============================================================

ALTER TABLE examens ADD COLUMN heure_debut TIME;

UPDATE examens SET heure_debut = '09:00:00' WHERE heure_debut IS NULL;
