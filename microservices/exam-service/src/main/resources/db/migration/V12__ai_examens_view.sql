-- =============================================================
-- V12 — vue de lecture du statut d'examen pour le module IA (#359, ADR-0029 D2)
--
-- « Le calcul ne porte que sur des examens CLOS (v1) » — mais aucune vue
-- n'exposait examens.statut : la garde examen-clos d'ai-service était
-- invérifiable (constat S44-bis §5a). Cette vue est la source de vérité de
-- cette garde. Le statut est un ÉTAT PERSISTÉ écrit par un acte humain
-- (Terminer, gated par toutesVaguesTerminees) — jamais une dérivation
-- d'horloge (ADR-0014) : la garde IA hérite de cette propriété.
--
-- matiere_id / nom / date_examen accompagnent le statut pour l'en-tête de
-- réponse — l'IA ne lit toujours AUCUNE table, seulement des vues.
--
-- PRÉREQUIS : le rôle ai_reader existe (infrastructure/init-db/init2-ai.sh) —
-- le GRANT vit dans db/vendor/postgresql/V13__ai_reader_grants_examens.sql
-- (DO $$ / pg_roles / GRANT = Postgres pur que le H2 des tests ne parse pas ;
-- ce fichier-ci reste portable, la vue se crée aussi sur H2).
-- =============================================================

CREATE OR REPLACE VIEW v_ai_examens AS
SELECT
    e.id          AS examen_id,
    e.statut,
    e.matiere_id,
    e.nom,
    e.date_examen
FROM examens e;
