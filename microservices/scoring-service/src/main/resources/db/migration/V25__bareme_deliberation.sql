-- =============================================================
-- V25 — Barème de délibération ADDITIF et versionné (ADR-0030, issue #361)
--
-- Second artefact PAR-DESSUS le snapshot gelé d'ADR-0015 : le jury peut
-- exclure un critère/une station ou repondérer, sans JAMAIS toucher au
-- snapshot ni aux score_final stockés (le recalcul est de PRÉSENTATION,
-- à la lecture — ADR-0030 D4). Écrit par le responsable seul (garde
-- matière+rôle, modèle du réajustement ADR-0013) ; l'IA n'a aucun chemin
-- d'écriture ici (ADR-0029 D2).
--
-- Sémantique de version (ADR-0030 D3) : lignes IMMUABLES — corriger, c'est
-- écrire une nouvelle version ; revenir au barème d'origine, c'est une
-- version explicitement VIDE (aucune opération). La contrainte
-- uq_bareme_deliberation_examen_version est aussi la garde de course :
-- deux POST concurrents calculent le même max+1, un seul commit passe.
--
-- Cross-service note : examen_id -> exam_db.examens.id et
-- cree_par -> auth_db.users.id (FK logiques, sans contrainte — précédent
-- V1 rotation.evaluateur_id / V5 adjusted_by_user_id). Les cibles des
-- opérations référencent les ids du SNAPSHOT (exam_item_snapshot.item_id /
-- exam_grille_snapshot.station_id) : le barème délibéré se définit par
-- rapport à ce qui a réellement servi à noter, pas la grille vivante.
-- bareme_id est une vraie FK en base avec ON DELETE CASCADE (la table
-- parente vit ici) — mais aucun DELETE n'existe dans le code.
-- =============================================================

CREATE TABLE bareme_deliberation (
    id          BIGSERIAL     PRIMARY KEY,
    examen_id   BIGINT        NOT NULL,
    version     INTEGER       NOT NULL,
    motif       VARCHAR(1000) NOT NULL,
    cree_par    BIGINT        NOT NULL,
    created_at  TIMESTAMP     NOT NULL,
    CONSTRAINT uq_bareme_deliberation_examen_version UNIQUE (examen_id, version)
);

CREATE INDEX idx_bareme_deliberation_examen_id ON bareme_deliberation(examen_id);

CREATE TABLE bareme_deliberation_operation (
    id                BIGSERIAL PRIMARY KEY,
    bareme_id         BIGINT    NOT NULL REFERENCES bareme_deliberation(id) ON DELETE CASCADE,
    -- Énumération FERMÉE : exactement les trois opérations d'ADR-0021 D8.
    type              VARCHAR(32) NOT NULL
        CHECK (type IN ('EXCLURE_CRITERE', 'EXCLURE_STATION', 'REPONDERER')),
    cible_item_id     BIGINT,
    cible_station_id  BIGINT,
    -- REPONDERER seulement : la nouvelle échelle (> 0), les autres types NULL.
    nouvelle_echelle  DOUBLE PRECISION
);

CREATE INDEX idx_bareme_deliberation_operation_bareme_id
    ON bareme_deliberation_operation(bareme_id);
