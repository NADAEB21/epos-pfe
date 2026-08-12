-- V9__exam_grille_snapshot.sql
-- ADR-0015 (extension #244) — snapshot write-once de la STRUCTURE de grille
-- (nom, noteMax, hiérarchie complète des items), pour que l'écran de
-- notation mobile n'ait plus besoin d'appeler exam-service directement.
--
-- Contrairement à exam_item_snapshot (une ligne par item FEUILLE, pour le
-- calcul du score), cette table stocke la grille ENTIÈRE telle que reçue
-- de exam-service (GET /api/stations/{id}/grille), y compris la hiérarchie
-- critère/sous-critère (#160), en JSON brut : rien côté scoring-service
-- n'a besoin d'interroger cette structure, seulement de la SERVIR telle
-- quelle au client mobile — d'où l'absence de colonnes structurées.

CREATE TABLE exam_grille_snapshot (
                                      id           BIGSERIAL PRIMARY KEY,
                                      examen_id    BIGINT       NOT NULL,
                                      station_id   BIGINT       NOT NULL,
                                      grille_id    BIGINT       NOT NULL,
                                      nom          VARCHAR(255) NOT NULL,
                                      note_max     DOUBLE PRECISION NOT NULL,
                                      items_json   TEXT         NOT NULL,
                                      captured_at  TIMESTAMP    NOT NULL,

                                      CONSTRAINT uq_exam_grille_snapshot_station UNIQUE (station_id)
);

-- Sert l'invalidation #183 ("dé-lancer" EN_COURS → CONFIGURE), qui purge
-- tous les snapshots d'un examen pour forcer leur re-copie au relancement.
CREATE INDEX idx_exam_grille_snapshot_examen ON exam_grille_snapshot (examen_id);