-- #134 — le catalogue des matieres cesse d'etre gere au SQL.
--
-- Constat : la liste des matieres est nee d'un INSERT dans init.sql et c'est
-- reste le SEUL moyen d'en ajouter une. Le super-admin — dont c'est le domaine
-- legitime (ADR-0018 D5 : « gestion des matieres ») — ne peut ni creer, ni
-- renommer, ni fermer une matiere depuis l'application. Sur une installation
-- neuve (PC de la faculte), demarrer une matiere exige un terminal Docker.
--
-- Ce que la migration ajoute : le RETRAIT d'une matiere, calque sur le retrait
-- d'un compte (#289, V3) — motive, attribue, reversible. PAS de suppression :
-- matiere_id traverse les services en cle logique SANS contrainte SQL
-- (ADR-0006) ; un DELETE orphelinerait silencieusement les examens passes et
-- leurs resultats afficheraient « Matiere 7 » au lieu d'un nom.
--
--   matieres.active           : false = retiree. Elle disparait des listes de
--     choix (creation d'examen, nomination d'un responsable) mais son nom
--     continue d'exister pour l'historique.
--   matieres.retired_at/_by/_motif : qui a ferme cette matiere, quand,
--     pourquoi — portes sur la ligne (et pas seulement dans l'audit) parce que
--     l'ecran doit pouvoir l'AFFICHER six mois plus tard, comme pour un compte.

ALTER TABLE matieres ADD COLUMN active           BOOLEAN      NOT NULL DEFAULT TRUE;
ALTER TABLE matieres ADD COLUMN retired_at       TIMESTAMP    NULL;
ALTER TABLE matieres ADD COLUMN retired_by       BIGINT       NULL;
ALTER TABLE matieres ADD COLUMN retirement_motif VARCHAR(500) NULL;

COMMENT ON COLUMN matieres.active           IS '#134 — false = matiere retiree du catalogue actif. Jamais de DELETE (ADR-0006 : references logiques inter-services).';
COMMENT ON COLUMN matieres.retired_at       IS '#134 — quand la matiere a ete retiree. NULL si active.';
COMMENT ON COLUMN matieres.retired_by       IS '#134 — l''administrateur qui a decide. Meme exigence de provenance que users.deactivated_by (#289).';
COMMENT ON COLUMN matieres.retirement_motif IS '#134 — motif OBLIGATOIRE au retrait, comme pour un compte (#289).';
