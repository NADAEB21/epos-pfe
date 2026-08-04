-- #289 — desactiver un compte cesse d'etre un clic anonyme, inexplique et definitif.
--
-- Constat (Nada, deux fois) : « c'est absurde qu'un admin puisse prendre une
-- decision aussi lourde d'un clic ». Verifie : aucun motif demande, aucun acteur
-- enregistre (la ligne d'audit nomme la CIBLE, jamais celui qui agit), aucune
-- annulation possible. Une suppleance d'evaluateur en pleine epreuve, acte plus
-- leger, exige DEJA un motif et trace son auteur (ADR-0017) : l'ecart n'est pas
-- defendable.
--
-- Ce que la migration ajoute :
--   users.deactivated_at / _by / _motif : qui a ferme ce compte, quand, pourquoi.
--     Portes sur l'utilisateur (et pas seulement dans l'audit) parce que l'ecran
--     doit pouvoir l'AFFICHER a cote de la ligne, six mois plus tard.
--   audit_logs.acteur_id : la reponse a « qui a fait ca ? », absente jusqu'ici
--     pour toute action administrative (ADR-0018 D3).

ALTER TABLE users ADD COLUMN deactivated_at    TIMESTAMP     NULL;
ALTER TABLE users ADD COLUMN deactivated_by    BIGINT        NULL;
ALTER TABLE users ADD COLUMN deactivation_motif VARCHAR(500) NULL;

ALTER TABLE audit_logs ADD COLUMN acteur_id BIGINT NULL;

COMMENT ON COLUMN users.deactivated_at     IS '#289 — quand le compte a ete retire. NULL si actif.';
COMMENT ON COLUMN users.deactivated_by     IS '#289 — l''administrateur qui a decide. Sans lui, « pourquoi ce compte est-il ferme ? » reste sans reponse.';
COMMENT ON COLUMN users.deactivation_motif IS '#289 — motif OBLIGATOIRE a la desactivation (meme exigence qu''ADR-0017 pour une suppleance).';
COMMENT ON COLUMN audit_logs.acteur_id     IS '#289 / ADR-0018 D3 — QUI a agi. user_id designe la CIBLE de l''action, pas son auteur.';

-- Les comptes deja retires gardent un motif inconnu : on ne fabrique pas une
-- justification apres coup. La colonne reste NULL et l'ecran le dira honnetement.
