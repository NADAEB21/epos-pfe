-- =============================================================
-- exam-service initial schema
-- Owns: examens, stations, grilles_evaluation, items_evaluation
-- Cascade chain: examen -> stations -> grille -> items
-- =============================================================

CREATE TABLE examens (
                         id                        BIGSERIAL    PRIMARY KEY,
                         nom                       VARCHAR(150) NOT NULL,
                         matiere                   VARCHAR(100) NOT NULL,
                         date_examen               DATE         NOT NULL,
                         duree_station_min         INTEGER      NOT NULL,
                         nb_etudiants_par_station  INTEGER      NOT NULL,
                         statut                    VARCHAR(20)  NOT NULL,
                         pdf_sujet_path            VARCHAR(255),
                         pdf_sujet_nom             VARCHAR(255),
                         description               VARCHAR(500),
                         created_at                TIMESTAMP    NOT NULL DEFAULT NOW(),
                         updated_at                TIMESTAMP
);

CREATE TABLE stations (
                          id           BIGSERIAL    PRIMARY KEY,
                          nom          VARCHAR(150) NOT NULL,
                          type         VARCHAR(20)  NOT NULL,
                          ordre        INTEGER      NOT NULL,
                          description  VARCHAR(300),
                          examen_id    BIGINT       NOT NULL REFERENCES examens(id) ON DELETE CASCADE,
                          created_at   TIMESTAMP    NOT NULL DEFAULT NOW(),
                          updated_at   TIMESTAMP
);

CREATE INDEX idx_stations_examen_id ON stations(examen_id);

CREATE TABLE grilles_evaluation (
                                    id          BIGSERIAL    PRIMARY KEY,
                                    nom         VARCHAR(150) NOT NULL,
                                    note_max    DOUBLE PRECISION NOT NULL,
                                    description VARCHAR(300),
                                    station_id  BIGINT       NOT NULL UNIQUE REFERENCES stations(id) ON DELETE CASCADE,
                                    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
                                    updated_at  TIMESTAMP
);

CREATE TABLE items_evaluation (
                                  id           BIGSERIAL    PRIMARY KEY,
                                  libelle      VARCHAR(300) NOT NULL,
                                  type         VARCHAR(20)  NOT NULL,
                                  ponderation  DOUBLE PRECISION NOT NULL,
                                  valeur_max   DOUBLE PRECISION,
                                  ordre        INTEGER      NOT NULL,
                                  categorie    VARCHAR(100),
                                  grille_id    BIGINT       NOT NULL REFERENCES grilles_evaluation(id) ON DELETE CASCADE,
                                  created_at   TIMESTAMP    NOT NULL DEFAULT NOW(),
                                  updated_at   TIMESTAMP
);

CREATE INDEX idx_items_grille_id ON items_evaluation(grille_id);