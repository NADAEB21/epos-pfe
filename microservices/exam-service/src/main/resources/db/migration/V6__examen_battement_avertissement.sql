-- =============================================================
-- V6 — Examen : tampon inter-créneau + avertissement de passage (ADR-0012)
--
-- Le circuit OSCE était généré sans aucun intervalle entre les passages : à
-- la fin d'un créneau, le suivant démarrait immédiatement, sans transition ni
-- préavis (remarque du professeur, ~2026-06-24). ADR-0012 ajoute deux durées
-- de configuration sur l'examen :
--
--   temps_battement_min    : tampon de transition (changeover) entre deux
--                            créneaux, en minutes. Reformate le planning —
--                            l'unité de créneau devient (duree + battement)
--                            côté scoring (RotationGenerationService).
--   avertissement_lead_sec : délai, en secondes, AVANT le prochain passage
--                            auquel l'avertissement se déclenche sur l'appareil
--                            de l'évaluateur. 0 = avertissements désactivés.
--
-- Les deux valent 0 par défaut → rétro-compatibilité totale : tout examen
-- existant régénère un planning identique (battement 0) et n'émet aucun
-- avertissement (lead 0). Les lignes debut_creneau déjà générées restent
-- valides (battement 0 implicite). Aucune reprise de données nécessaire.
--
-- Note de numérotation (ADR-0012 §1) : cette migration prend V6. ADR-0011
-- (heure_fin, multi-jours, encore Proposed) n'ayant pas livré, il prendra V7.
-- =============================================================

ALTER TABLE examens ADD COLUMN temps_battement_min INTEGER NOT NULL DEFAULT 0;
ALTER TABLE examens ADD COLUMN avertissement_lead_sec INTEGER NOT NULL DEFAULT 0;
