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
