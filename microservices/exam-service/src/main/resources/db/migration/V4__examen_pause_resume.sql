-- =============================================================
-- V4 — Examen pause/reprise (ADR-0009)
--
-- Pause is modelled as orthogonal state, NOT a lifecycle status: a paused exam
-- is still EN_COURS. These columns let the Suivi screen compute "effective exam
-- time" (wall-clock minus accumulated paused time) so the pre-computed
-- back-to-back rotation schedule stays valid across breaks / pauses / multi-day
-- gaps. See ADR-0009.
--
--   en_pause        : currently paused?
--   paused_at       : when the current pause began (NULL unless en_pause)
--   total_pause_sec : accumulated seconds over all COMPLETED pause intervals
--
-- Existing rows: not paused, zero accumulated pause — safe defaults.
-- =============================================================

ALTER TABLE examens ADD COLUMN en_pause BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE examens ADD COLUMN paused_at TIMESTAMP;
ALTER TABLE examens ADD COLUMN total_pause_sec INTEGER NOT NULL DEFAULT 0;
