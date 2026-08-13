-- V6__tokens_invalid_before.sql
-- #306 — l'estampille de révocation : « les jetons de cet utilisateur émis AVANT cet
-- instant sont morts ».
--
-- Posée par tout acte de sécurité : retrait d'accès, changement de rôles, changement ou
-- réinitialisation de mot de passe. Comparée au claim `iat` du jeton par chaque service
-- (liste distribuée périodiquement — voir /internal/revocations et TokenRevocationList).
--
-- NULL = jamais révoqué (l'écrasante majorité des lignes), d'où l'index PARTIEL : la
-- requête de distribution ne balaie que les utilisateurs récemment révoqués.

ALTER TABLE users ADD COLUMN tokens_invalid_before TIMESTAMP;

CREATE INDEX idx_users_tokens_invalid_before
    ON users (tokens_invalid_before)
    WHERE tokens_invalid_before IS NOT NULL;
