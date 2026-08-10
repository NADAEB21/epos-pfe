-- =============================================================
-- V17 — Matière d'un examen, figée localement (#274, ADR-0015, ADR-0018 D5)
--
-- POURQUOI CETTE TABLE EXISTE
-- scoring-service ne connaissait AUCUNE notion de matière : ni colonne, ni champ,
-- ni DTO. Conséquence mesurée sur #274 : la seule garde des actes de conduite du
-- jour J était `hasAnyRole('SUPER_ADMIN','RESPONSABLE_MATIERE')` — le rôle NU,
-- sans matière. Un responsable de Toxicologie pouvait ouvrir une vague, démarrer
-- un lot et écraser une note d'une épreuve de Chimie thérapeutique. Autoriser par
-- matière exige donc de répondre localement à : « à quelle matière appartient
-- l'examen N ? »
--
-- POURQUOI FIGÉ ET NON DEMANDÉ À CHAQUE ÉCRITURE
-- Interroger exam-service par écriture ferait tomber l'AUTORISATION avec lui —
-- exactement la régression qu'ADR-0015 interdit : l'instantané existe pour que les
-- écritures du jour J ne dépendent pas d'exam-service. La matière d'un examen est
-- IMMUABLE une fois l'examen lancé (`Examen.matiere_id`, exam_db), donc la figer
-- ne perd aucune fraîcheur utile. Ce n'est pas un cache d'état vivant : c'est un
-- attribut figé, du même genre que les pondérations de V8.
--
-- QUAND LA LIGNE EST ÉCRITE — et la prémisse qu'il a fallu corriger
-- Le document de conception disait « capturé au lancement ». C'est impossible en
-- l'état : **il n'existe aucun chemin d'appel exam → scoring** (constat d'ADR-0020,
-- dont `invalidateExam` n'a aucun appelant), et `ExamenServiceImpl.changerStatut`
-- pose `statut` + `launched_at` puis rend la main. La capture est donc
-- PARESSEUSE et STRICTE, comme V8 : écrite à la première écriture d'un
-- responsable sur cet examen — laquelle a nécessairement lieu en PRÉPARATION,
-- exam-service debout. Le jour J la ligne existe déjà : chemin 100 % local.
--
-- L'ARÊTE VIVE, assumée : un examen dont la toute première écriture scoring
-- surviendrait pendant une panne d'exam-service est REFUSÉ, bruyamment. Refuser
-- est réversible ; autoriser hors périmètre ne l'est pas.
--
-- POURQUOI UNE TABLE ET NON UNE COLONNE SUR exam_station_snapshot
-- Le grain y est faux : cette table est clé sur `station_id UNIQUE`, alors que
-- l'autorisation part d'un `Lot`, qui porte `examen_id` et AUCUNE station. Et une
-- colonne `matiere_id NOT NULL` est impossible à ajouter à une table déjà peuplée.
--
-- `examen_id` et `matiere_id` sont des FK LOGIQUES cross-DB — même précédent
-- qu'ADR-0006 : les lignes sources vivent dans exam_db et auth_db.
-- =============================================================

CREATE TABLE exam_matiere_snapshot (
    id          BIGSERIAL PRIMARY KEY,
    examen_id   BIGINT    NOT NULL,
    matiere_id  BIGINT    NOT NULL,
    captured_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_matiere_snapshot_examen UNIQUE (examen_id)
);

-- La contrainte UNIQUE porte déjà l'index de lecture (examen_id est le seul
-- prédicat : « la matière de l'examen N »). Aucun index supplémentaire.

COMMENT ON TABLE exam_matiere_snapshot IS
    'Copie write-once de examens.matiere_id (exam_db), par ADR-0015. Sert '
    'l''AUTORISATION par matière dans scoring-service (#274) : sans elle, un '
    'responsable d''une autre matière conduit une épreuve qui n''est pas la '
    'sienne. Ne jamais y figer une valeur de repli — en cas d''échec amont on '
    'n''écrit rien et l''appel est refusé.';
