-- =============================================================
-- V5 — Audited réajustement trail (ADR-0013 Part 2, issues #23 / #135)
--
-- scoring-service's FIRST audit table. Records every controlled change made
-- to a (locked) notation through POST /api/notations/{id}/reajustement — the
-- only sanctioned path that may touch a verrouillée notation (student
-- réclamation). Each row: who / when / which item / old→new value / old→new
-- score / why. Turns the notation lock (#23) into a real integrity boundary.
--
-- Cross-service note: adjusted_by_user_id -> auth_db.users.id (logical FK,
-- no constraint — same precedent as rotation.evaluateur_id in V1).
-- notation_id is a real in-DB FK (notations lives here) with ON DELETE CASCADE
-- so the trail disappears with the notation it describes.
-- =============================================================

CREATE TABLE notation_adjustments (
    id                   BIGSERIAL PRIMARY KEY,
    notation_id          BIGINT       NOT NULL REFERENCES notations(id) ON DELETE CASCADE,
    item_id              BIGINT,
    ancienne_valeur      REAL,
    nouvelle_valeur      REAL,
    ancien_score         REAL,
    nouveau_score        REAL,
    motif                VARCHAR(1000) NOT NULL,
    adjusted_by_user_id  BIGINT       NOT NULL,
    adjusted_at          TIMESTAMP    NOT NULL
);

CREATE INDEX idx_notation_adjustments_notation_id ON notation_adjustments(notation_id);
