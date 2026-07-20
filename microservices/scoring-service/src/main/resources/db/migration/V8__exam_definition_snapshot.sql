-- =============================================================
-- V8 — Exam definition snapshot (ADR-0015, issues #240 / #200)
--
-- WHY THIS EXISTS
-- scoring-service used to fetch station names and item pondérations from
-- exam-service over the network ON EVERY REQUEST. Those values are immutable
-- by construction: `Examen.isGrilleModifiable()` (exam_db) is true ONLY for
-- BROUILLON/CONFIGURE, so from EN_COURS onward the definition cannot change.
-- Fetching them live bought nothing and cost three silent failure modes when
-- exam-service was unavailable (reproduced live 2026-07-20):
--   * station names degraded to the placeholder "Station <id>";
--   * BINAIRE items scored RAW (valeur 1 × pondération 5 → 1 instead of 5),
--     persisted to score_final and broadcast over WebSocket, with no error;
--   * the leaf guard fell open, letting a parent criterion be graded and then
--     double-counted permanently.
--
-- These tables hold a WRITE-ONCE copy, materialised on first successful use.
-- This is NOT a cache:
--   * it lives in the database  → survives restarts/deploys, shared by replicas;
--   * it is write-once          → a grade means what it meant on exam day;
--   * it is never refreshed     → correct, because edits are already blocked
--                                 from EN_COURS onward.
-- If the source fetch fails, NOTHING is written — the caller fails loudly.
-- A missing snapshot must never degrade into a placeholder or an unweighted score.
--
-- ⚠️ NO CLOCK AUTHORITY (ADR-0015 §4 guardrail, ADR-0014 / ADR-0014-A).
-- DEFINITION only — names, types, pondérations. No timing, no statut, no derived
-- state. `debutCreneau` stays an indicative PLAN, and `duree_station_min` is
-- deliberately NOT stored here: sizing a countdown is a FLOOR (the student gets
-- their time), never a CEILING (nothing may retire a session because time passed).
--
-- Cross-DB references (examen_id, station_id, item_id, grille_id) are LOGICAL
-- FKs — same precedent as ADR-0006 (matiere_id) and station_evaluateurs.
-- No SQL FK is possible: the source rows live in exam_db.
--
-- SCOPE NOTE (verified before writing — no unfillable columns):
-- the only upstream sources are
--   GET /api/stations/{id}                     → { nom, examenId }
--   GET /api/grilles/{grilleId}/items/feuilles → ItemInfo(id, ponderation, type)
-- The latter returns ONLY LEAVES ("Aplatit l'arbre : ne renvoie que les
-- feuilles" — GrilleService:55). There is therefore no `parent_id`/`ordre` to
-- store, and none is needed: **the snapshot IS the notable-item set.** An item
-- absent from `exam_item_snapshot` is, by definition, not notable — which lets
-- the leaf guard become local and UNCONDITIONAL instead of skipping itself when
-- a remote lookup returns empty (`saisirNotation:227` fail-open).
-- =============================================================

CREATE TABLE exam_station_snapshot (
    id          BIGSERIAL PRIMARY KEY,
    examen_id   BIGINT       NOT NULL,
    station_id  BIGINT       NOT NULL,
    nom         VARCHAR(255) NOT NULL,
    captured_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_station_snapshot_station UNIQUE (station_id)
);

-- examen_id exists so #183 "dé-lancer" (EN_COURS → CONFIGURE) can invalidate an
-- exam's whole snapshot: a relaunch must re-copy, or an edited grille would be
-- graded against the stale copy. This is the design's one sharp edge.
CREATE INDEX idx_station_snapshot_examen ON exam_station_snapshot (examen_id);

CREATE TABLE exam_item_snapshot (
    id          BIGSERIAL PRIMARY KEY,
    examen_id   BIGINT       NOT NULL,
    grille_id   BIGINT       NOT NULL,
    item_id     BIGINT       NOT NULL,
    type        VARCHAR(32)  NOT NULL,   -- BINAIRE | NUMERIQUE
    ponderation DOUBLE PRECISION NOT NULL,
    captured_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_item_snapshot_item UNIQUE (item_id)
);

-- recalculerScoreFinal reads a whole grille at once; #183 invalidation is by exam.
CREATE INDEX idx_item_snapshot_grille ON exam_item_snapshot (grille_id);
CREATE INDEX idx_item_snapshot_examen ON exam_item_snapshot (examen_id);

COMMENT ON TABLE exam_item_snapshot IS
    'Write-once copy of a grille''s NOTABLE (leaf) items, per ADR-0015. Presence '
    'here is the authority on what may be graded: an item absent from this table '
    'is not notable. recalculerScoreFinal must FAIL on an unknown item rather '
    'than score it raw — scoring it raw is how a parent criterion double-counts.';
