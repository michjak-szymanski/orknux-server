-- Says where the function came from rather than how far it reaches.
--
-- V64 called this scope ORGANIZATION, which described the visibility and hid the
-- thing worth knowing. A plugin declaring it is the only way one of these comes
-- to exist, so "organisation" named a category with a single member — and a
-- function picker offering "isTeammate (organization)" tells somebody nothing
-- about where it came from or who to ask about it. PLUGIN says it.
--
-- Nothing carries the old value yet: no plugin has been materialised into a
-- function, so there are no rows to rewrite, only the rules that mention it.
ALTER TABLE workflow_function
    DROP CONSTRAINT ck_workflow_function_scope,
    DROP CONSTRAINT ck_workflow_function_owner;

ALTER TABLE workflow_function
    ADD CONSTRAINT ck_workflow_function_scope CHECK (scope IN ('WORKSPACE', 'PLUGIN')),
    ADD CONSTRAINT ck_workflow_function_owner CHECK (
        (scope = 'WORKSPACE' AND workspace_id IS NOT NULL AND plugin_id IS NULL)
        OR (scope = 'PLUGIN' AND workspace_id IS NULL AND plugin_id IS NOT NULL)
    );

DROP INDEX uk_workflow_function_organisation_name;

CREATE UNIQUE INDEX uk_workflow_function_plugin_name
    ON workflow_function (name)
    WHERE scope = 'PLUGIN';
