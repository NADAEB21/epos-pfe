-- =============================================================
-- V21 (POSTGRESQL SEULEMENT) — droits du rôle ai_reader sur les vues V20
-- (#353, ADR-0029 D2). Ce dossier vit HORS de db/migration : Flyway scanne
-- ses locations récursivement, un sous-dossier de db/migration serait vu
-- par le H2 des tests (payé une fois). Ici : db/vendor/{vendor}, résolu
-- par Spring en db/vendor/postgresql en prod et db/vendor/h2 (absent,
-- ignoré) sous test.
--
-- ⚠️ Numérotation : V21 est PRISE par ce fichier — la prochaine migration
-- commune de scoring est V22.
--
-- PRÉREQUIS : le rôle ai_reader existe (infrastructure/init-db/init2-ai.sh,
-- automatique sur volume neuf, manuel sur volume existant). Sinon : échec
-- FORT avec le message ci-dessous — voulu, jamais un GRANT silencieusement
-- sauté.
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
    GRANT SELECT ON v_ai_notations_verrouillees, v_ai_exam_matiere TO ai_reader;
END $$;
