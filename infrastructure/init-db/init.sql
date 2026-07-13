-- =============================================================
-- EPOS — PostgreSQL initialisation script
-- Runs automatically when the Docker container is first created.
-- Safe to re-run: uses IF NOT EXISTS / ON CONFLICT DO NOTHING.
-- =============================================================

-- -------------------------------------------------------------
-- 1. Databases (one per microservice)
--
-- auth_db schema is defined inline below (auth-service owns the
-- DDL here for historical reasons + seed data needs).
--
-- exam_db and scoring_db are created empty: their schemas are
-- owned by Flyway and applied on first service startup
-- (src/main/resources/db/migration/V1__init_*.sql in each service).
-- Do NOT add CREATE TABLE statements here for those DBs.
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

-- Pharmacy subjects (matières). Reference table consumed by:
--   * user_roles.matiere_id  (RESPONSABLE_MATIERE scope)
--   * exam-service.examens.matiere_id  (cross-service logical FK)
CREATE TABLE IF NOT EXISTS matieres (
    id         BIGSERIAL    PRIMARY KEY,
    code       VARCHAR(20)  NOT NULL UNIQUE,
    libelle    VARCHAR(100) NOT NULL,
    created_at TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS user_roles (
    id         BIGSERIAL   PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES users(id),
    role       VARCHAR(50) NOT NULL,
    matiere_id BIGINT      REFERENCES matieres(id)  -- nullable; non-null only for RESPONSABLE_MATIERE
);

-- Idempotent FK install for DBs created before matieres existed.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.table_constraints
        WHERE constraint_name = 'user_roles_matiere_id_fkey'
          AND table_name = 'user_roles'
    ) THEN
        ALTER TABLE user_roles
            ADD CONSTRAINT user_roles_matiere_id_fkey
            FOREIGN KEY (matiere_id) REFERENCES matieres(id);
    END IF;
END$$;

-- A user legitimately holds several roles (a RESPONSABLE_MATIERE is often also an
-- EVALUATEUR), but never the SAME role twice. Plain UNIQUE(user_id, role, matiere_id)
-- would not do: Postgres treats NULLs as distinct, so it would happily allow two
-- EVALUATEUR rows (matiere_id NULL) for one user. COALESCE collapses that hole.
-- Purge any pre-existing duplicates first, keeping the oldest row of each group.
DELETE FROM user_roles a
      USING user_roles b
      WHERE a.id > b.id
        AND a.user_id = b.user_id
        AND a.role    = b.role
        AND COALESCE(a.matiere_id, -1) = COALESCE(b.matiere_id, -1);

CREATE UNIQUE INDEX IF NOT EXISTS ux_user_roles_user_role_matiere
    ON user_roles (user_id, role, COALESCE(matiere_id, -1));

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
-- 4. Seed matieres (must precede user_roles seed — FK target)
--    Order matters: id=1 is referenced by resp@epos.tn below.
-- -------------------------------------------------------------

INSERT INTO matieres (code, libelle) VALUES
    ('CHIM_THER',  'Chimie thérapeutique'),
    ('PHARMACO',   'Pharmacologie'),
    ('PHAG',       'Pharmacognosie'),
    ('TOXICO',     'Toxicologie'),
    ('GALENIQUE',  'Pharmacie galénique')
ON CONFLICT (code) DO NOTHING;


-- -------------------------------------------------------------
-- 5. Seed roles
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
