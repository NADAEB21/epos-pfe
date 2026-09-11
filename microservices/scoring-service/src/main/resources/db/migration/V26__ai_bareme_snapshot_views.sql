-- =============================================================
-- V26 — vues de lecture pour le moteur de proposition du module IA
-- (#362 / N8, ADR-0021 D8-D10, ADR-0030 D1)
--
-- Pourquoi ces vues existent : l'effet PROJETÉ d'une proposition (D10 —
-- « jamais découvert après coup ») doit être calculé avec EXACTEMENT les
-- entrées que BaremeDeliberationEngine applique à la lecture de /results :
--   1. la version COURANTE du barème de délibération (une proposition
--      acceptée s'ajoute à ce qui est déjà appliqué — les versions sont
--      complètes, pas incrémentales, ADR-0030 D3) ;
--   2. le snapshot de grille V9 : `note_max` (dénominateur ORIGINAL) et
--      `items_json` (`valeurMax` des critères NUMERIQUE — exam_item_snapshot
--      ne le porte pas, ADR-0015 définition minimale).
-- Sans ces vues, ai_reader ne voit ni l'un ni l'autre (V20/V23 : vues
-- seulement, jamais les tables) et le module projetterait contre la grille
-- VIVANTE d'exam_db au lieu du snapshot qui a réellement servi à noter.
--
-- Rien du motif ni de l'auteur n'est exposé : le module n'en a pas besoin,
-- l'audit humain vit dans scoring (GET .../bareme-deliberation).
-- LEFT JOIN délibéré : une version VIDE (retour à l'origine, D3) doit rester
-- visible — « pas de barème » ≠ « barème vide ».
--
-- PRÉREQUIS : le rôle ai_reader existe (init2-ai.sh) — le GRANT vit dans
-- db/vendor/postgresql/V27__ai_reader_grants_bareme.sql (Postgres pur,
-- invisible du H2 des tests). Ce fichier-ci reste portable.
-- =============================================================

CREATE OR REPLACE VIEW v_ai_bareme_deliberation AS
SELECT
    b.examen_id,
    b.version,
    b.created_at,
    o.type             AS op_type,
    o.cible_item_id,
    o.cible_station_id,
    o.nouvelle_echelle
FROM bareme_deliberation b
LEFT JOIN bareme_deliberation_operation o ON o.bareme_id = b.id;

CREATE OR REPLACE VIEW v_ai_grille_snapshot AS
SELECT
    examen_id,
    station_id,
    grille_id,
    note_max,
    items_json
FROM exam_grille_snapshot;
