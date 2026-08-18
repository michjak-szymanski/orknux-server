-- Functions that belong to the organisation rather than to a workspace.
--
-- A plugin is loaded once for the whole installation, so the functions it
-- declares are available in every workspace. That does not fit a table whose
-- rows have always belonged to exactly one workspace, so three things change.
--
-- First, the scope is written down rather than inferred. A null workspace would
-- have been enough to work out which is which, but "workspace_id IS NULL" is a
-- thing a reader has to decode; `scope` is a thing a reader can read, and every
-- query that has to include organisation functions says so in those words.
ALTER TABLE workflow_function
    ADD COLUMN scope VARCHAR(16) NOT NULL DEFAULT 'WORKSPACE',
    ADD CONSTRAINT ck_workflow_function_scope CHECK (scope IN ('WORKSPACE', 'ORGANIZATION'));

-- Second, an organisation function has no workspace to belong to.
ALTER TABLE workflow_function
    ALTER COLUMN workspace_id DROP NOT NULL;

-- Third, it belongs to the plugin that declared it, and goes when that goes.
ALTER TABLE workflow_function
    ADD COLUMN plugin_id BIGINT REFERENCES plugin (id) ON DELETE CASCADE;

-- The two are exclusive: a function is a workspace's or a plugin's, never both
-- and never neither. Stated here so no amount of application code can produce a
-- row that is half of each.
ALTER TABLE workflow_function
    ADD CONSTRAINT ck_workflow_function_owner CHECK (
        (scope = 'WORKSPACE' AND workspace_id IS NOT NULL AND plugin_id IS NULL)
        OR (scope = 'ORGANIZATION' AND workspace_id IS NULL AND plugin_id IS NOT NULL)
    );

-- The existing unique constraint is per workspace, and Postgres treats NULLs as
-- distinct — so it would let two plugins declare the same name. Organisation
-- names are held unique by their own index instead.
--
-- In practice a name arrives as `plugin_function`, and since plugin names are
-- unique and a plugin's own function names are unique, that cannot collide. The
-- index is here for the case the application forgets.
CREATE UNIQUE INDEX uk_workflow_function_organisation_name
    ON workflow_function (name)
    WHERE scope = 'ORGANIZATION';

CREATE INDEX idx_workflow_function_plugin ON workflow_function (plugin_id);
