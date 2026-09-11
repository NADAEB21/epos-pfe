-- =============================================================
-- V23 — comptage des notations verrouillées par examen, pour le module IA
-- (#359, ADR-0029 D2)
--
-- Pourquoi cette vue existe : v_ai_notations_verrouillees (V20) joint
-- notation_items en JOINTURE INTERNE — une notation verrouillée SANS AUCUN
-- item est structurellement invisible du chargement (piège documenté au
-- drill S44-bis, loader.py:12-16). Le moteur ne peut donc pas savoir
-- combien de jugements arrêtés il n'a PAS vus. Cette vue donne le total
-- de référence ; l'écart (total − notations chargées distinctes) devient
-- l'exclusion « sans_aucun_item », comptée et affichée au lieu de disparaître.
--
-- Même chaîne de jointure que V20 (assignment → participation → examen).
--
-- PRÉREQUIS : le rôle ai_reader existe (init2-ai.sh) — le GRANT vit dans
-- db/vendor/postgresql/V24__ai_reader_grants_totaux.sql (Postgres pur,
-- invisible du H2 des tests). Ce fichier-ci reste portable.
-- =============================================================

CREATE OR REPLACE VIEW v_ai_notations_totaux AS
SELECT
    ep.examen_id,
    COUNT(*) AS nb_verrouillees
FROM notations n
JOIN rotation_assignment   ra ON ra.id = n.assignment_id
JOIN examen_participations ep ON ep.id = ra.participation_id
WHERE n.verouillee IS TRUE
GROUP BY ep.examen_id;
