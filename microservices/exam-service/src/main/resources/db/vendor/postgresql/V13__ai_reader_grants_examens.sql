-- =============================================================
-- V13 (POSTGRESQL SEULEMENT) — droits du rôle ai_reader sur la vue V12
-- (#359, ADR-0029 D2). Même mécanique que V11 : ce dossier vit HORS de
-- db/migration parce que Flyway scanne ses locations récursivement — un
-- sous-dossier de db/migration serait vu par le H2 des tests (payé une
-- fois). Ici : db/vendor/{vendor}, résolu en db/vendor/postgresql en prod
-- et db/vendor/h2 (absent, ignoré) sous test.
--
-- ⚠️ Numérotation : V13 est PRISE par ce fichier — la prochaine migration
-- commune d'exam-service est V14.
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
    GRANT SELECT ON v_ai_examens TO ai_reader;
END $$;
