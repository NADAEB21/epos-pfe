-- =============================================================
-- V5 — Examen.launched_at : instant de lancement réel (ADR-0010)
--
-- Le moment où le responsable bascule l'examen en EN_COURS (Lancement) n'était
-- jamais enregistré : le Suivi reconstruisait le départ à partir de la date/heure
-- *planifiée* (dateExamen + heureDebut). Si l'examen démarre en retard (cas
-- réaliste en OSCE), le tableau de bord lisait « X minutes déjà écoulées » dès
-- l'ouverture. launched_at capture le départ effectif, ancre unique partagé par
-- le planning (Rotation.debutCreneau) et l'horloge live. Voir ADR-0010.
--
--   launched_at : posé UNE SEULE FOIS au passage → EN_COURS, jamais réécrit.
--                 NULL pour les lignes existantes (fallback départ planifié).
-- =============================================================

ALTER TABLE examens ADD COLUMN launched_at TIMESTAMP;
