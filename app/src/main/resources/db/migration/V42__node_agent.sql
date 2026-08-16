-- An agent node instances one of the workspace's agents, the way an action node
-- instances an action.
--
-- It used to hold two free-text fields from the mockups — an "agent class" and a
-- "model provider" — whose values were invented for the picture: nothing read
-- them, nothing could run them, and the model names in them ("GPT-4o (Orknux
-- Shared)") were never models this workspace had. They are dropped rather than
-- left dead: a column nobody reads is a column somebody will one day believe.
--
-- What replaces them is a pointer, and the agent supplies the rest — its model,
-- its instructions, the catalogs it was granted. That is the catalogue-then-
-- instance rule this codebase follows everywhere else.
ALTER TABLE workflow_node ADD COLUMN agent_id BIGINT REFERENCES agent (id);

ALTER TABLE workflow_node DROP COLUMN agent_class;
ALTER TABLE workflow_node DROP COLUMN model_provider;

CREATE INDEX idx_workflow_node_agent ON workflow_node (agent_id);

-- And the run keeps its own copy, like every other pointer a step carries: what
-- ran is a fact about that run, and editing the workflow afterwards must not
-- rewrite it.
ALTER TABLE execution_step ADD COLUMN agent_id BIGINT;
