-- =============================================================
-- V10 — vue de lecture du module IA (#353, ADR-0029 D2)
--
-- Pendant d'exam_db de la V20 de scoring : le snapshot scoring
-- (exam_item_snapshot) ne porte NI valeur_max NI libelle (vérifié S42),
-- or la difficulté d'un critère numérique est moyenne(valeur)/valeur_max.
-- Le rôle ai_reader n'a de SELECT que sur cette vue.
--
-- PRÉREQUIS : le rôle ai_reader existe (infrastructure/init-db/init2-ai.sh) —
-- sinon cette migration échoue avec un message clair (fail loud, voulu).
-- =============================================================

CREATE OR REPLACE VIEW v_ai_criteres AS
SELECT
    i.id          AS item_id,
    i.libelle,
    i.type,
    i.ponderation,
    i.valeur_max,
    i.ordre,
    i.parent_id,
    i.categorie,
    g.id          AS grille_id,
    g.note_max,
    g.station_id,
    s.examen_id
FROM items_evaluation i
JOIN grilles_evaluation g ON g.id = i.grille_id
JOIN stations           s ON s.id = g.station_id;

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
