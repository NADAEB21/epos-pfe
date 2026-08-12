-- =============================================================
-- V9 — Qui a lancé l'épreuve (#306, #217, ADR-0024 « le conducteur »)
--
-- POURQUOI
-- `launched_at` (V-, ADR-0010) dit QUAND une épreuve a été lancée. Rien ne dit
-- PAR QUI. Le système connaît donc l'instant de l'acte le plus lourd du produit
-- — celui qui fige la définition (ADR-0015) et ouvre la salle — sans en connaître
-- l'auteur.
--
-- Cela bloque deux tickets HAUTE d'un coup :
--   #306  retirer l'accès de quelqu'un qui CONDUIT une épreuve en cours ne doit
--         pas se faire en silence ; encore faut-il savoir qui conduit.
--   #217  même mécanisme côté verrou.
--
-- ⚠️ CE N'EST PAS UNE PERMISSION, C'EST UN FAIT OBSERVÉ.
-- `lance_par` enregistre qui a agi. Il ne donne aucun droit et ne doit JAMAIS
-- être lu comme une autorisation : le droit d'agir se décide par la matière
-- (#274, MatiereAccessChecker), jamais par « c'est lui qui a lancé ». Confondre
-- les deux ferait d'une trace d'audit un contrôle d'accès — exactement l'erreur
-- qu'ADR-0018 D5 reproche aux gardes lues comme des intentions.
--
-- Le lanceur n'est pas non plus « le conducteur » à lui seul : le conducteur est
-- l'auteur du DERNIER acte de conduite (ouverture de vague), et à défaut
-- seulement le lanceur. Le cas qui l'impose : B lance, rentre chez lui, C ouvre
-- la vague suivante. Retenir le lanceur protègerait un absent et laisserait
-- retirer le présent. La moitié « vague » vit dans scoring (V18, lot.ouvert_par).
--
-- FK LOGIQUE vers auth_db.users — pas de FK SQL possible (cross-service, même
-- précédent qu'ADR-0006 pour matiere_id). NULL = épreuve lancée avant cette
-- migration : on n'invente aucun auteur rétroactivement.
-- =============================================================

ALTER TABLE examens
    ADD COLUMN lance_par BIGINT;

COMMENT ON COLUMN examens.lance_par IS
    'FK logique vers auth_db.users — qui a fait passer l''examen a EN_COURS. '
    'Fait OBSERVE, jamais une permission : le droit d''agir se decide par la '
    'matiere (#274). NULL = lance avant la migration, aucun auteur invente.';
