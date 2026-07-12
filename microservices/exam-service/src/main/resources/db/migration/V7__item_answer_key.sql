-- =============================================================
-- V7 — Answer key / expected value per criterion (#162)
--
-- Adds an optional "corrigé" to each grille criterion and each grille-template
-- criterion: the expected numeric value and/or the expected conditions (free
-- text). Both nullable — existing grilles carry no answer key. Additive, no
-- backfill. Surfacing on web/mobile is a separate step (mobile gated on Feten).
-- =============================================================

ALTER TABLE items_evaluation ADD COLUMN valeur_attendue      DOUBLE PRECISION;
ALTER TABLE items_evaluation ADD COLUMN conditions_attendues VARCHAR(1000);

ALTER TABLE item_templates   ADD COLUMN valeur_attendue      DOUBLE PRECISION;
ALTER TABLE item_templates   ADD COLUMN conditions_attendues VARCHAR(1000);
