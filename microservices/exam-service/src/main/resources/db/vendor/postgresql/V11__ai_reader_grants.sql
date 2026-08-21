-- =============================================================
-- V11 (POSTGRESQL SEULEMENT) — droits du rôle ai_reader sur la vue V10
-- (#353, ADR-0029 D2). Ce dossier vit HORS de db/migration : Flyway scanne
-- ses locations récursivement, un sous-dossier de db/migration serait vu
-- par le H2 des tests (payé une fois). Ici : db/vendor/{vendor}, résolu
-- par Spring en db/vendor/postgresql en prod et db/vendor/h2 (absent,
-- ignoré) sous test.
--
-- ⚠️ Numérotation : V11 est PRISE par ce fichier — la prochaine migration
-- commune d'exam-service est V12.
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
    GRANT CONNECT ON DATABASE exam_db TO ai_reader;
    GRANT USAGE ON SCHEMA public TO ai_reader;
    GRANT SELECT ON v_ai_criteres TO ai_reader;
END $$;
