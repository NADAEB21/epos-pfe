-- =============================================================
-- V24 (POSTGRESQL SEULEMENT) — droits du rôle ai_reader sur la vue V23
-- (#359, ADR-0029 D2). Même mécanique que V21 : dossier db/vendor/{vendor},
-- HORS de db/migration (Flyway scanne récursivement — le H2 des tests
-- verrait un sous-dossier ; payé une fois, collision V21 prouvée au boot
-- en S44). Résolu en db/vendor/postgresql en prod, db/vendor/h2 absent
-- donc ignoré sous test.
--
-- ⚠️ Numérotation : V24 est PRISE par ce fichier — la prochaine migration
-- commune de scoring-service est V25.
--
-- PRÉREQUIS : le rôle ai_reader existe (infrastructure/init-db/init2-ai.sh) —
-- sinon échec FORT avec le message ci-dessous (voulu).
-- =============================================================

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ai_reader') THEN
        RAISE EXCEPTION 'Le rôle ai_reader n''existe pas — exécuter '
            'infrastructure/init-db/init2-ai.sh AVANT de déployer cette version '
            '(voir ai-service/README.md, § Amorçage).';
    END IF;
    GRANT CONNECT ON DATABASE scoring_db TO ai_reader;
    GRANT USAGE ON SCHEMA public TO ai_reader;
    GRANT SELECT ON v_ai_notations_totaux TO ai_reader;
END $$;
