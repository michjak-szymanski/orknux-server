-- A trigger that belongs to one workflow: the same "Custom" an action and a
-- condition already take, for the same reason. See V258, which says why the
-- ownership is a column here rather than a definition stored on the node.
ALTER TABLE workflow_trigger
    ADD COLUMN workflow_id bigint REFERENCES workflow (id) ON DELETE CASCADE;

CREATE INDEX idx_workflow_trigger_workflow ON workflow_trigger (workflow_id);
