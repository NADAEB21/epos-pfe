-- =============================================================
-- V6 — Student complaint register (réclamations, issue #136)
--
-- The responsable's logbook of student complaints about exam results. It sits
-- ON TOP of the audited réajustement channel (V5 / ADR-0013 Part 2): that table
-- records score CHANGES; this one records the COMPLAINTS — including the ones
-- that are REJECTED and therefore produce no change, which V5 alone could not
-- capture.
--
-- Filing actor: RESPONSABLE_MATIERE / SUPER_ADMIN (students have no login), so
-- created_by_user_id -> auth_db.users.id (logical FK, no constraint — same
-- precedent as notation_adjustments.adjusted_by_user_id).
--
-- participation_id is a real in-DB FK (examen_participations lives in this
-- schema) with ON DELETE CASCADE — a complaint disappears with the participation
-- it concerns. examen_id / notation_id / adjustment_id are logical FKs only.
-- =============================================================

CREATE TABLE reclamations (
    id                    BIGSERIAL     PRIMARY KEY,
    examen_id             BIGINT        NOT NULL,
    participation_id      BIGINT        NOT NULL REFERENCES examen_participations(id) ON DELETE CASCADE,
    notation_id           BIGINT,
    objet                 VARCHAR(1000) NOT NULL,
    statut                VARCHAR(20)   NOT NULL DEFAULT 'EN_ATTENTE',
    reponse               VARCHAR(1000),
    adjustment_id         BIGINT,
    created_by_user_id    BIGINT        NOT NULL,
    created_at            TIMESTAMP     NOT NULL,
    resolved_by_user_id   BIGINT,
    resolved_at           TIMESTAMP
);

CREATE INDEX idx_reclamations_examen_id ON reclamations(examen_id);
CREATE INDEX idx_reclamations_participation_id ON reclamations(participation_id);
