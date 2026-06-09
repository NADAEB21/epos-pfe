-- =============================================================
-- V3 — examen_participations: composite unique (examen_id, etudiant_id)
--
-- Prevents enrolling the same student twice on one exam (double roster row,
-- double-graded). Phase C data-integrity fold-in #3. Verified 2026-06-09:
-- 0 existing (examen_id, etudiant_id) duplicates, so the constraint applies
-- cleanly to the current volume.
--
-- NOTE: the sibling numero_inscription unique constraint (#2) is intentionally
-- NOT added here — the etudiants table currently holds triplicated rows
-- (18 numbers × 3) and the constraint would fail Flyway validate on boot.
-- It needs a data + seed cleanup first; deferred.
-- =============================================================

ALTER TABLE examen_participations
    ADD CONSTRAINT uq_participation_examen_etudiant UNIQUE (examen_id, etudiant_id);
