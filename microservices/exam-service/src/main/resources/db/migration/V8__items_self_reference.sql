-- exam_db
ALTER TABLE items_evaluation
    ADD COLUMN parent_id BIGINT REFERENCES items_evaluation(id) ON DELETE CASCADE;
CREATE INDEX idx_items_evaluation_parent ON items_evaluation(parent_id);

ALTER TABLE item_templates
    ADD COLUMN parent_id BIGINT REFERENCES item_templates(id) ON DELETE CASCADE;
CREATE INDEX idx_item_templates_parent ON item_templates(parent_id);