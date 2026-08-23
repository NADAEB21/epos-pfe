-- =============================================================
-- V18 — Qui a ouvert la vague (#306, #217, ADR-0024 « le conducteur »)
--
-- POURQUOI
-- V9 a donné à ce lot son instant d'ouverture (`ouvert_a`, #252). Il manquait
-- l'auteur. Le système savait donc QUAND chaque vague s'est ouverte, jamais PAR QUI
-- — et « qui conduit l'épreuve en ce moment » n'était calculable nulle part.
--
-- C'EST CETTE COLONNE QUI DÉSIGNE LE CONDUCTEUR, pas `examens.lance_par`.
-- Le conducteur est l'auteur du DERNIER acte de conduite ; le lanceur ne sert que
-- par défaut, quand aucune vague n'a encore été ouverte. Le cas qui l'impose est
-- réel : B lance l'épreuve, rentre chez lui, C ouvre la vague suivante. Retenir le
-- lanceur protègerait un absent et laisserait retirer le présent — l'inverse exact
-- du but de #306.
--
-- Précédent maison : #209 / `rotation.debut_reel` — le minuteur démarre quand
-- l'évaluateur ouvre RÉELLEMENT le groupe, jamais au créneau prévu. Le projet a déjà
-- tranché en faveur de l'observé contre le déclaré ; c'est la même règle appliquée
-- à la conduite.
--
-- ⚠️ CE N'EST PAS UNE PERMISSION.
-- `ouvert_par` enregistre qui a agi. Le DROIT d'agir se décide par la matière
-- (#274, MatiereAccessGuard), jamais par « c'est lui qui a ouvert la dernière
-- vague ». Lire cette colonne comme une autorisation ferait d'une trace d'audit un
-- contrôle d'accès — précisément le glissement qu'ADR-0018 D5 reproche aux gardes
-- lues comme des intentions. Et A4 (#274) devait être corrigé AVANT celle-ci,
-- sinon « le conducteur » pourrait désigner quelqu'un qui n'avait pas le droit
-- d'agir : c'est fait, livré en V17.
--
-- FK LOGIQUE vers auth_db.users — pas de FK SQL (cross-service, précédent ADR-0006).
-- NULL = vague ouverte avant cette migration : aucun auteur inventé rétroactivement,
-- exactement comme `ouvert_a` l'a fait en V9.
-- =============================================================

ALTER TABLE lot
    ADD COLUMN ouvert_par BIGINT;

COMMENT ON COLUMN lot.ouvert_par IS
    'FK logique vers auth_db.users — qui a ouvert cette vague. Avec ouvert_a, '
    'les deux moities du meme fait. C''est CETTE colonne qui designe le '
    'conducteur (l''auteur du dernier acte de conduite), pas examens.lance_par. '
    'Fait OBSERVE, jamais une permission. NULL = ouverte avant la migration.';
