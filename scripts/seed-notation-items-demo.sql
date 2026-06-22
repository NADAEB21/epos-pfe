-- Demo per-criterion breakdown for exam 16 (gap #3 — Résultats per-critère deep-dive).
--
-- The mobile évaluateur app (unbuilt) is what would normally write notation_items
-- alongside each Notation. seed-notations-demo.sql only fills notations.score_final,
-- leaving notation_items EMPTY — so the Résultats per-critère drill-down has nothing
-- to show. This script seeds a representative, internally-consistent breakdown for the
-- rank-1 student (participation 83) across all four stations, so the deep-dive renders
-- real critère scores for that student. Other cells legitimately show the empty
-- "saisie globale" state until the mobile app lands.
--
-- valeur convention (mirrors the grille item types):
--   BINAIRE   → 0 (non acquis) or 1 (acquis); contributes its ponderation when acquis.
--   NUMERIQUE → awarded points in [0, valeur_max].
-- Each notation's item values sum to its notations.score_final.
--
-- Idempotent: wipes the four target notations' items before re-inserting.
-- Apply: docker exec -i epos-postgres psql -U admin -d scoring_db < scripts/seed-notation-items-demo.sql

DELETE FROM notation_items WHERE notation_id IN (49, 7, 21, 35);

-- Notation 49 — station 26 (grille 15), score_final 17
INSERT INTO notation_items (notation_id, item_id, valeur, commentaire) VALUES
  (49, 55, 1, NULL),
  (49, 56, 1, NULL),
  (49, 57, 5, 'Identification correcte mais justification incomplète'),
  (49, 58, 2, NULL);

-- Notation 7 — station 27 (grille 16), score_final 12
INSERT INTO notation_items (notation_id, item_id, valeur, commentaire) VALUES
  (7, 59, 1, NULL),
  (7, 60, 4, NULL),
  (7, 61, 3, 'Calcul stœchiométrique partiellement erroné'),
  (7, 62, 0, 'Point d''équivalence manqué');

-- Notation 21 — station 28 (grille 17), score_final 15
INSERT INTO notation_items (notation_id, item_id, valeur, commentaire) VALUES
  (21, 63, 1, NULL),
  (21, 64, 5, NULL),
  (21, 65, 6, NULL),
  (21, 66, 0, NULL);

-- Notation 35 — station 29 (grille 18), score_final 18
INSERT INTO notation_items (notation_id, item_id, valeur, commentaire) VALUES
  (35, 67, 1, NULL),
  (35, 68, 4, NULL),
  (35, 69, 5, NULL),
  (35, 70, 4, NULL);
