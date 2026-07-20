-- =============================================================
-- auth_db baseline schema.
--
-- Historically auth_db's DDL lived ONLY in infrastructure/init-db/init.sql,
-- which Postgres runs exactly once per fresh data volume. Combined with
-- spring.jpa.hibernate.ddl-auto=validate that made the schema unevolvable:
-- adding a field to User failed validation at startup and auth-service — the
-- login path for the whole platform — refused to boot until someone ALTERed
-- the table by hand on the server.
--
-- This file is the Flyway baseline that ends that. It reproduces exactly what
-- init.sql creates, so:
--   * existing databases (dev volumes, the AWS deployment) are already at this
--     state and get baselined at V1 without re-running it — see
--     spring.flyway.baseline-version in application.yml;
--   * an empty database (a new environment, or a future managed Postgres with
--     no init.sql) is built from here, so auth-service can now bootstrap itself.
--
-- From now on every auth_db change is a new V<n> migration, NOT an edit to
-- init.sql and NOT an edit to this file. Same contract exam-service and
-- scoring-service have followed since their own V1.
--
-- Retrofit statements from init.sql (the conditional FK install and the
-- duplicate-role purge) are deliberately omitted: they only ever existed to
-- repair databases predating the matieres table, which cannot be the case for
-- a schema created from scratch here.
-- =============================================================

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

-- Pharmacy subjects. Reference table consumed by:
--   * user_roles.matiere_id            (RESPONSABLE_MATIERE scope)
--   * exam-service.examens.matiere_id  (cross-service logical FK, see ADR-0006)
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

-- A user legitimately holds several roles (a RESPONSABLE_MATIERE is often also
-- an EVALUATEUR) but never the SAME role twice. A plain
-- UNIQUE(user_id, role, matiere_id) would not do: Postgres treats NULLs as
-- distinct, so it would happily allow two EVALUATEUR rows (matiere_id NULL) for
-- one user. COALESCE collapses that hole.
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
