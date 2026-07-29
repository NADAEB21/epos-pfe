-- ADR-0017 — trace des suppléances d'évaluateur.
--
-- Jusqu'ici, changer l'évaluateur d'une rotation n'était possible QUE par
-- l'écriture générique de RotationController (:77, :99) : sans garde, rotation
-- par rotation, sans distinguer le travail fini du travail restant, et SANS
-- AUCUNE TRACE. Une capacité non conçue n'est pas un mécanisme de suppléance.
--
-- Remplacer un évaluateur en pleine épreuve est un acte d'organisation lourd :
-- il doit pouvoir être expliqué après coup, comme un réajustement de note
-- (ADR-0013). D'où le motif OBLIGATOIRE.
CREATE TABLE evaluateur_substitution (
    id                  BIGSERIAL PRIMARY KEY,
    lot_id              BIGINT      NOT NULL REFERENCES lot(id),
    station_id          BIGINT      NOT NULL,   -- FK logique vers exam_db
    ancien_evaluateur   BIGINT      NOT NULL,   -- FK logique vers auth_db
    nouvel_evaluateur   BIGINT      NOT NULL,
    rotations_transferees INTEGER   NOT NULL,
    motif               VARCHAR(500) NOT NULL,
    decide_par          BIGINT      NOT NULL,   -- le responsable qui a décidé
    survenu_a           TIMESTAMP   NOT NULL
);

CREATE INDEX idx_substitution_lot ON evaluateur_substitution(lot_id);

COMMENT ON TABLE evaluateur_substitution IS
    'ADR-0017 — qui a remplacé qui, sur quelle station, pourquoi, et combien de groupes ont changé de main.';
