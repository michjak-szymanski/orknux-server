-- A trigger is a definition in the team's catalogue: what event, on what
-- connection or schedule. It does not name a workflow — a workflow does the
-- naming, by pointing one of its trigger nodes at a definition. That node is
-- the instance; this table is the catalogue.
ALTER TABLE workflow_trigger
    DROP COLUMN workflow_id;

ALTER TABLE workflow_node
    ADD COLUMN trigger_id BIGINT REFERENCES workflow_trigger (id) ON DELETE SET NULL;

-- What an arriving event asks second: which workflows use this definition?
CREATE INDEX idx_workflow_node_trigger ON workflow_node (trigger_id) WHERE trigger_id IS NOT NULL;
