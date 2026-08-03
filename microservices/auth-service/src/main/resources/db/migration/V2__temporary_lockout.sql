-- #294 — le verrou après 3 échecs devient TEMPORAIRE, et cesse d'emprunter le
-- drapeau de la désactivation administrative.
--
-- Avant cette migration, `UserRepository.lockAccount` posait `is_active=false`,
-- exactement comme `UserService.deactivateUser`. Deux causes opposées, un seul
-- drapeau, aucune trace de laquelle : aucun écran ne pouvait conseiller juste
-- (les remèdes sont opposés — débloquer vs remplacer), et le verrou était
-- DÉFINITIF. Trois requêtes non authentifiées suffisaient à exclure
-- définitivement le propriétaire d'une adresse connue.
--
-- Après :
--   is_active     = décision ADMINISTRATIVE uniquement (départ, retrait). Rien
--                   d'automatique ne l'écrit plus.
--   locked_until  = verrou TEMPORAIRE anti-force-brute. Expire tout seul.
--   lock_count    = nombre de verrous consécutifs ; sert au backoff exponentiel
--                   (2, 4, 8, 16 min… plafonné). Remis à zéro par une connexion
--                   réussie, en même temps que le compteur d'échecs.

ALTER TABLE users ADD COLUMN locked_until TIMESTAMP NULL;
ALTER TABLE users ADD COLUMN lock_count   INTEGER NOT NULL DEFAULT 0;

COMMENT ON COLUMN users.is_active    IS '#294 — désactivation ADMINISTRATIVE seule. Le verrouillage anti-force-brute utilise locked_until.';
COMMENT ON COLUMN users.locked_until IS '#294 — verrou temporaire : la connexion est refusée tant que now() < locked_until. NULL = pas de verrou.';
COMMENT ON COLUMN users.lock_count   IS '#294 — verrous consécutifs, pour le backoff exponentiel. Remis à 0 à la connexion réussie.';

-- Les comptes déjà inactifs sont traités comme DÉSACTIVÉS administrativement.
-- C'est la lecture prudente : on ne peut pas savoir, après coup, lequel des deux
-- chemins les a fermés, et ré-ouvrir automatiquement un compte réellement retiré
-- serait le seul des deux risques irréversible. Ceux qui étaient en réalité
-- verrouillés devront être rouverts par l'administration (#289).
-- On remet en revanche le compteur d'échecs à zéro : il ne veut plus rien dire.
UPDATE users SET failed_login_attempts = 0 WHERE is_active = false;

-- Retrouver rapidement les verrous encore actifs (supervision, écrans admin).
CREATE INDEX idx_users_locked_until ON users (locked_until) WHERE locked_until IS NOT NULL;
