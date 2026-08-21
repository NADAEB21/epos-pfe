-- =============================================================
-- V20 — vues de lecture du module IA (#353, ADR-0029 D2)
--
-- Les vues vivent ICI, chez le propriétaire des tables : une migration
-- future qui casse une colonne échoue FORT dans ce service au lieu de
-- pourrir silencieusement côté ai-service. Le rôle ai_reader n'a de
-- SELECT que sur ces vues — jamais sur les tables.
--
-- PRÉREQUIS : le rôle ai_reader existe (infrastructure/init-db/init2-ai.sh,
-- automatique sur volume neuf, manuel sur volume existant). Sans lui cette
-- migration échoue avec le message ci-dessous — c'est voulu (fail loud).
-- =============================================================

-- Une ligne par critère noté d'une notation VERROUILLÉE — la matière première
-- des indices (ADR-0008). Les notations non verrouillées sont hors champ :
-- l'analyse ne porte que sur des jugements arrêtés.
CREATE OR REPLACE VIEW v_ai_notations_verrouillees AS
SELECT
    ems.examen_id,
    n.station_id,
    n.grille_id,
    n.id           AS notation_id,
    n.score_final,
    n.saisi_par,
    ra.participation_id,
    ep.etudiant_id,
    ni.item_id,
    ni.valeur,
    ems.type       AS item_type,
    ems.ponderation
FROM notations n
JOIN rotation_assignment   ra  ON ra.id = n.assignment_id
JOIN examen_participations ep  ON ep.id = ra.participation_id
JOIN notation_items        ni  ON ni.notation_id = n.id
JOIN exam_item_snapshot    ems ON ems.item_id = ni.item_id
WHERE n.verouillee IS TRUE;

-- Résolution examen → matière : la même vérité que MatiereAccessGuard (#274).
CREATE OR REPLACE VIEW v_ai_exam_matiere AS
SELECT examen_id, matiere_id
FROM exam_matiere_snapshot;

-- Les GRANT au rôle ai_reader (+ le contrôle d'existence du rôle) vivent dans
-- db/vendor/postgresql/V21__ai_reader_grants.sql : DO $$ / pg_roles / GRANT
-- sont du Postgres pur que le H2 des tests ne sait pas parser. Ce fichier-ci
-- reste portable (les vues se créent aussi sur H2 — prouvé par la suite).
