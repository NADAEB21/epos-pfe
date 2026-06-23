ALTER TABLE examen_participations
    ADD COLUMN IF NOT EXISTS commentaire VARCHAR(500);