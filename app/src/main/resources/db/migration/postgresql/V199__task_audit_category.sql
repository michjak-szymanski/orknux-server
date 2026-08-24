-- Tasks get a category of their own in the audit log.
--
-- Not folded into AGENT, although a task is an agent working. What somebody
-- filters this log for is "what did this task do and who let it": a task
-- started, a task stopped, and - the one that matters - a capability granted to
-- one task by one person. Read among every save of every agent's configuration,
-- those are the rows nobody finds.
ALTER TABLE workspace_audit DROP CONSTRAINT ck_workspace_audit_category;
ALTER TABLE workspace_audit ADD CONSTRAINT ck_workspace_audit_category
    CHECK (category IN ('WORKSPACE', 'WORKFLOW', 'AGENT', 'INTEGRATION', 'MODEL', 'MEMORY', 'OBJECT', 'CHAT', 'SHELL', 'TASK'));
