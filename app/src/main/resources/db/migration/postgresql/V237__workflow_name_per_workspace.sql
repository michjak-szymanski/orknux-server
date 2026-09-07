-- Workflow names are unique within a workspace, not across the installation, so
-- the same name can be created in two workspaces. The workspace a definition
-- belongs to lives on its assignment (workspace_workflow), not on the workflow
-- row, so the uniqueness is enforced in the application against the assignments
-- the way every other per-workspace name is. Here the installation-wide
-- constraint is dropped. Issues #338, #341.
ALTER TABLE workflow DROP CONSTRAINT uk_workflow_name;
