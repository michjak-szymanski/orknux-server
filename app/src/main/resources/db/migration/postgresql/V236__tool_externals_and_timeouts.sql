-- A tool is handed the workspace's variables the way a function is: after the
-- parameters it declares, chosen by id, never shown to the agent. The same
-- shape workflow_function_external has, because it is the same arrangement.
CREATE TABLE agent_tool_external (
    tool_id BIGINT NOT NULL REFERENCES agent_tool (id) ON DELETE CASCADE,
    variable_id BIGINT NOT NULL REFERENCES workspace_variable (id) ON DELETE RESTRICT,
    position INT NOT NULL,
    PRIMARY KEY (tool_id, position)
);

CREATE INDEX idx_agent_tool_external_variable ON agent_tool_external (variable_id);

-- How long one run may hold its thread, decided closest to the code first: the
-- tool's or function's own number, then the workspace's default, then the
-- installation's bound. Null everywhere is exactly what every run had before.
ALTER TABLE agent_tool ADD COLUMN timeout_seconds INT;
ALTER TABLE workflow_function ADD COLUMN timeout_seconds INT;
ALTER TABLE workspace ADD COLUMN script_timeout_seconds INT;
