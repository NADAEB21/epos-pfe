-- =============================================================
-- scoring-service initial schema
-- Owns: etudiants, examen_participations, lot, student_group,
--       rotation, rotation_assignment, notations, notation_items
--
-- Cross-service references (kept as plain BIGINT, no FK constraints —
-- the targets live in other services' databases):
--   examen_participations.examen_id  -> exam_db.examens.id
--   lot.examen_id                    -> exam_db.examens.id
--   lot.evaluateur_id                -> auth_db.users.id
--   rotation.evaluateur_id           -> auth_db.users.id
--   notation_items.item_id           -> exam_db.items_evaluation.id
-- =============================================================

CREATE TABLE etudiants (
    id                  BIGSERIAL PRIMARY KEY,
    numero_inscription  VARCHAR(255),
    nom                 VARCHAR(255),
    prenom              VARCHAR(255)
);

CREATE TABLE lot (
    id             BIGSERIAL PRIMARY KEY,
    examen_id      BIGINT,
    evaluateur_id  BIGINT,
    numero_lot     INTEGER,
    taille_lot     INTEGER,
    statut         VARCHAR(20)
);

CREATE TABLE student_group (
    id             BIGSERIAL PRIMARY KEY,
    numero_groupe  INTEGER,
    lot_id         BIGINT REFERENCES lot(id)
);

CREATE INDEX idx_student_group_lot_id ON student_group(lot_id);

CREATE TABLE examen_participations (
    id              BIGSERIAL PRIMARY KEY,
    examen_id       BIGINT,
    num_echantillon VARCHAR(255),
    note            REAL,
    est_present     BOOLEAN,
    etudiant_id     BIGINT REFERENCES etudiants(id),
    lot_id          BIGINT REFERENCES lot(id)
);

CREATE INDEX idx_participations_etudiant_id ON examen_participations(etudiant_id);
CREATE INDEX idx_participations_lot_id      ON examen_participations(lot_id);

CREATE TABLE rotation (
    id                BIGSERIAL PRIMARY KEY,
    evaluateur_id     BIGINT,
    ordre_passage     INTEGER,
    debut_creneau     TIMESTAMP,
    statut            VARCHAR(20),
    student_group_id  BIGINT REFERENCES student_group(id) ON DELETE CASCADE
);

CREATE INDEX idx_rotation_student_group_id ON rotation(student_group_id);

CREATE TABLE rotation_assignment (
    id                  BIGSERIAL PRIMARY KEY,
    presence_confirmee  BOOLEAN,
    temps_additionnel   INTEGER,
    rotation_id         BIGINT REFERENCES rotation(id) ON DELETE CASCADE,
    participation_id    BIGINT REFERENCES examen_participations(id)
);

CREATE INDEX idx_assignment_rotation_id      ON rotation_assignment(rotation_id);
CREATE INDEX idx_assignment_participation_id ON rotation_assignment(participation_id);

CREATE TABLE notations (
    id                 BIGSERIAL PRIMARY KEY,
    score_final        REAL,
    timestamp          TIMESTAMP,
    temps_additionnel  INTEGER,
    is_synced          BOOLEAN,
    verouillee         BOOLEAN,
    assignment_id      BIGINT UNIQUE REFERENCES rotation_assignment(id) ON DELETE CASCADE
);

CREATE TABLE notation_items (
    id           BIGSERIAL PRIMARY KEY,
    item_id      BIGINT,
    valeur       REAL,
    commentaire  VARCHAR(255),
    notation_id  BIGINT REFERENCES notations(id) ON DELETE CASCADE
);

CREATE INDEX idx_notation_items_notation_id ON notation_items(notation_id);
