-- An action or a condition that belongs to one workflow.
--
-- "Custom", as a node offers it: a definition made where it is used, by
-- somebody who wanted this one node to do a thing rather than to add a name to
-- a library the whole workspace reads. Null is what every existing row is --
-- shared, listed on the workspace's page, pointable-at by anything.
--
-- A row either way, deliberately. What a node points at is an id, and the
-- runner, the validator, the export and the revisions all read one, so owning
-- the row differently costs nothing downstream. Storing the definition on the
-- node instead would mean teaching every one of those a second shape for where
-- a definition comes from.
--
-- ON DELETE CASCADE because the row has no life without its workflow: nothing
-- else may point at it, so nothing else can be left dangling.
ALTER TABLE workflow_action
    ADD COLUMN workflow_id bigint REFERENCES workflow (id) ON DELETE CASCADE;
ALTER TABLE workflow_condition
    ADD COLUMN workflow_id bigint REFERENCES workflow (id) ON DELETE CASCADE;

CREATE INDEX idx_workflow_action_workflow ON workflow_action (workflow_id);
CREATE INDEX idx_workflow_condition_workflow ON workflow_condition (workflow_id);
