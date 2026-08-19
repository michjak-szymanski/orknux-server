-- A team is now a workspace, everywhere.
--
-- The word changed, not the thing: the same rows, the same relationships, the
-- same directory group deciding who may see one. Renames rather than a rebuild,
-- so nothing is copied and nothing can be lost on the way.
--
-- Postgres keeps a constraint's name when its table is renamed, so the names
-- are renamed too; otherwise the schema would read `team_pkey` on a table
-- called workspace. The names Postgres generated for itself — the `*_not_null`
-- and `*_fkey` ones — are left as they are: nothing refers to them, and they
-- are regenerated under the new names by anything that rebuilds this schema.

-- The audit category is an enum on both sides, so the values move with it.
ALTER TABLE team_audit
    DROP CONSTRAINT ck_team_audit_category;

UPDATE team_audit
SET category = 'WORKSPACE'
WHERE category = 'TEAM';

-- Entries were written in the words of the day, and the words have changed.
UPDATE team_audit
SET message = 'Workspace ' || substring(message from 6)
WHERE message LIKE 'Team %';

-- ---- Columns ----

ALTER TABLE agent RENAME COLUMN team_id TO workspace_id;
ALTER TABLE agent_skill RENAME COLUMN team_id TO workspace_id;
ALTER TABLE agent_tool RENAME COLUMN team_id TO workspace_id;
ALTER TABLE mcp_server RENAME COLUMN team_id TO workspace_id;
ALTER TABLE model_provider RENAME COLUMN team_id TO workspace_id;
ALTER TABLE team_audit RENAME COLUMN team_id TO workspace_id;
ALTER TABLE team_audit RENAME COLUMN old_team_name TO old_workspace_name;
ALTER TABLE team_audit RENAME COLUMN new_team_name TO new_workspace_name;
ALTER TABLE team_connection RENAME COLUMN team_id TO workspace_id;
ALTER TABLE team_connection_header RENAME COLUMN team_connection_id TO workspace_connection_id;
ALTER TABLE team_workflow RENAME COLUMN team_id TO workspace_id;
ALTER TABLE workflow_action RENAME COLUMN team_id TO workspace_id;
ALTER TABLE workflow_condition RENAME COLUMN team_id TO workspace_id;
ALTER TABLE workflow_execution RENAME COLUMN team_id TO workspace_id;
ALTER TABLE workflow_function RENAME COLUMN team_id TO workspace_id;
ALTER TABLE workflow_trigger RENAME COLUMN team_id TO workspace_id;

-- ---- Tables ----

ALTER TABLE team RENAME TO workspace;
ALTER TABLE team_audit RENAME TO workspace_audit;
ALTER TABLE team_connection RENAME TO workspace_connection;
ALTER TABLE team_connection_header RENAME TO workspace_connection_header;
ALTER TABLE team_workflow RENAME TO workspace_workflow;

-- ---- Constraints ----

ALTER TABLE workspace RENAME CONSTRAINT uk_team_name TO uk_workspace_name;
ALTER TABLE workspace RENAME CONSTRAINT team_pkey TO workspace_pkey;
ALTER TABLE workspace_audit RENAME CONSTRAINT ck_team_audit_operation_type TO ck_workspace_audit_operation_type;
ALTER TABLE workspace_audit RENAME CONSTRAINT team_audit_pkey TO workspace_audit_pkey;
ALTER TABLE workspace_connection RENAME CONSTRAINT uk_team_connection_name TO uk_workspace_connection_name;
ALTER TABLE workspace_connection RENAME CONSTRAINT ck_team_connection_auth TO ck_workspace_connection_auth;
ALTER TABLE workspace_connection RENAME CONSTRAINT ck_team_connection_type TO ck_workspace_connection_type;
ALTER TABLE workspace_connection RENAME CONSTRAINT ck_team_connection_check TO ck_workspace_connection_check;
ALTER TABLE workspace_connection RENAME CONSTRAINT team_connection_pkey TO workspace_connection_pkey;
ALTER TABLE workspace_connection_header
    RENAME CONSTRAINT team_connection_header_pkey TO workspace_connection_header_pkey;
ALTER TABLE workspace_workflow RENAME CONSTRAINT uk_team_workflow TO uk_workspace_workflow;
ALTER TABLE workspace_workflow RENAME CONSTRAINT team_workflow_pkey TO workspace_workflow_pkey;
ALTER TABLE agent RENAME CONSTRAINT uk_agent_team_name TO uk_agent_workspace_name;
ALTER TABLE mcp_server RENAME CONSTRAINT uk_mcp_server_team_name TO uk_mcp_server_workspace_name;

ALTER TABLE workspace_audit
    ADD CONSTRAINT ck_workspace_audit_category
        CHECK (category IN ('WORKSPACE', 'WORKFLOW', 'AGENT', 'INTEGRATION', 'MODEL'));

-- ---- Indexes ----

ALTER INDEX idx_team_audit_category RENAME TO idx_workspace_audit_category;
ALTER INDEX idx_team_audit_team_id RENAME TO idx_workspace_audit_workspace_id;
ALTER INDEX idx_team_connection_team_id RENAME TO idx_workspace_connection_workspace_id;
ALTER INDEX idx_team_workflow_team_id RENAME TO idx_workspace_workflow_workspace_id;
ALTER INDEX idx_agent_team_id RENAME TO idx_agent_workspace_id;
ALTER INDEX idx_agent_skill_team RENAME TO idx_agent_skill_workspace;
ALTER INDEX idx_agent_tool_team RENAME TO idx_agent_tool_workspace;
ALTER INDEX idx_mcp_server_team_id RENAME TO idx_mcp_server_workspace_id;
ALTER INDEX idx_model_provider_team RENAME TO idx_model_provider_workspace;
ALTER INDEX idx_workflow_action_team RENAME TO idx_workflow_action_workspace;
ALTER INDEX idx_workflow_condition_team RENAME TO idx_workflow_condition_workspace;
ALTER INDEX idx_workflow_execution_team RENAME TO idx_workflow_execution_workspace;
ALTER INDEX idx_workflow_function_team RENAME TO idx_workflow_function_workspace;
ALTER INDEX idx_workflow_trigger_team RENAME TO idx_workflow_trigger_workspace;
