-- =============================================================
-- EPOS — PostgreSQL initialisation script
-- Runs automatically when the Docker container is first created.
-- Safe to re-run: uses IF NOT EXISTS / ON CONFLICT DO NOTHING.
-- =============================================================

-- -------------------------------------------------------------
-- 1. Databases (one per microservice)
-- -------------------------------------------------------------
CREATE DATABASE auth_db;
CREATE DATABASE exam_db;
CREATE DATABASE scoring_db;


-- =============================================================
-- auth_db schema + seed data
-- =============================================================
\connect auth_db

-- -------------------------------------------------------------
-- 2. Tables
-- -------------------------------------------------------------

CREATE TABLE IF NOT EXISTS users (
    id                    BIGSERIAL    PRIMARY KEY,
    email                 VARCHAR(255) NOT NULL UNIQUE,
    password_hash         VARCHAR(255) NOT NULL,
    nom                   VARCHAR(100) NOT NULL,
    prenom                VARCHAR(100) NOT NULL,
    is_active             BOOLEAN      NOT NULL DEFAULT TRUE,
    failed_login_attempts INT          NOT NULL DEFAULT 0,
    created_at            TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS user_roles (
    id         BIGSERIAL   PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES users(id),
    role       VARCHAR(50) NOT NULL,
    matiere_id BIGINT      -- nullable; non-null only for RESPONSABLE_MATIERE
);

CREATE TABLE IF NOT EXISTS refresh_tokens (
    id         BIGSERIAL    PRIMARY KEY,
    user_id    BIGINT       NOT NULL REFERENCES users(id),
    token_hash VARCHAR(255) NOT NULL UNIQUE,
    expires_at TIMESTAMP    NOT NULL,
    revoked    BOOLEAN      NOT NULL DEFAULT FALSE,
    family_id  VARCHAR(255) NOT NULL
);

CREATE TABLE IF NOT EXISTS password_reset_tokens (
    id         BIGSERIAL    PRIMARY KEY,
    user_id    BIGINT       NOT NULL REFERENCES users(id),
    token_hash VARCHAR(255) NOT NULL,
    expires_at TIMESTAMP    NOT NULL,
    used       BOOLEAN      NOT NULL DEFAULT FALSE
);

CREATE TABLE IF NOT EXISTS audit_logs (
    id         BIGSERIAL    PRIMARY KEY,
    user_id    BIGINT,                          -- nullable: pre-auth failures have no user
    email      VARCHAR(255) NOT NULL,
    action     VARCHAR(50)  NOT NULL,
    details    TEXT,
    ip_address VARCHAR(50),
    created_at TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_audit_user_id    ON audit_logs(user_id);
CREATE INDEX IF NOT EXISTS idx_audit_created_at ON audit_logs(created_at);


-- -------------------------------------------------------------
-- 3. Seed users (bcrypt cost 10, Spring Security compatible)
--
--   admin@epos.tn  →  Admin@1234
--   resp@epos.tn   →  Resp@1234
--   eval@epos.tn   →  Eval@1234
-- -------------------------------------------------------------

INSERT INTO users (email, password_hash, nom, prenom, is_active, failed_login_attempts, created_at)
VALUES
    ('admin@epos.tn',
     '$2b$10$ua4O.tkqPeW97vY3Xv1a8.Uvut/hcHla3siMH/WijxtfT8r2ilIQe',
     'Ben Ali',   'Aymen', TRUE, 0, NOW()),

    ('resp@epos.tn',
     '$2b$10$Z/6OUz4A7B/2/D.i29byGeyu2Jv.9QiqLn7VYSJahO6pciV99XtOu',
     'Trabelsi',  'Sonia', TRUE, 0, NOW()),

    ('eval@epos.tn',
     '$2b$10$fhda0BiXRG7i9.QxOvCDq.nfJRu/uheWOO8s7gHYnY6yMYBNO7nta',
     'Mzoughi',   'Karim', TRUE, 0, NOW())
ON CONFLICT (email) DO NOTHING;


-- -------------------------------------------------------------
-- 4. Seed roles
-- -------------------------------------------------------------

-- SUPER_ADMIN — matiere_id must be NULL
INSERT INTO user_roles (user_id, role, matiere_id)
SELECT id, 'SUPER_ADMIN', NULL
FROM users WHERE email = 'admin@epos.tn'
  AND NOT EXISTS (
      SELECT 1 FROM user_roles ur
      JOIN users u ON u.id = ur.user_id
      WHERE u.email = 'admin@epos.tn' AND ur.role = 'SUPER_ADMIN'
  );

-- RESPONSABLE_MATIERE — matiere_id required (using 1 as placeholder)
INSERT INTO user_roles (user_id, role, matiere_id)
SELECT id, 'RESPONSABLE_MATIERE', 1
FROM users WHERE email = 'resp@epos.tn'
  AND NOT EXISTS (
      SELECT 1 FROM user_roles ur
      JOIN users u ON u.id = ur.user_id
      WHERE u.email = 'resp@epos.tn' AND ur.role = 'RESPONSABLE_MATIERE'
  );

-- EVALUATEUR — matiere_id must be NULL
INSERT INTO user_roles (user_id, role, matiere_id)
SELECT id, 'EVALUATEUR', NULL
FROM users WHERE email = 'eval@epos.tn'
  AND NOT EXISTS (
      SELECT 1 FROM user_roles ur
      JOIN users u ON u.id = ur.user_id
      WHERE u.email = 'eval@epos.tn' AND ur.role = 'EVALUATEUR'
  );
