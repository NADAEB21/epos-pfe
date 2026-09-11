-- =============================================================
-- V27 (POSTGRESQL SEULEMENT) — droits du rôle ai_reader sur les vues V26
-- (#362 / N8, ADR-0029 D2). Même mécanique que V21/V24 : dossier
-- db/vendor/{vendor}, HORS de db/migration (Flyway scanne récursivement —
-- le H2 des tests verrait un sous-dossier). Résolu en db/vendor/postgresql
-- en prod, db/vendor/h2 absent donc ignoré sous test.
--
-- ⚠️ Numérotation : V27 est PRISE par ce fichier — la prochaine migration
-- commune de scoring-service est V28.
--
-- SELECT seulement : ai_reader lit le barème courant et le snapshot, il
-- n'écrit jamais dans scoring (le barème reste l'acte du responsable,
-- ADR-0030 D1).
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
    GRANT SELECT ON v_ai_bareme_deliberation TO ai_reader;
    GRANT SELECT ON v_ai_grille_snapshot TO ai_reader;
END $$;
