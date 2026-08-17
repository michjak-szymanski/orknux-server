-- What a workspace does inside itself is not an organisation-level change.
--
-- The admin log keeps entries with no workspace plus the WORKSPACE category,
-- which had quietly become a catch-all: attaching a file to a chat and choosing
-- a workspace's companion model both landed there, and both then showed up in a
-- log that is meant to say what happened to the organisation. Chats get a
-- category of their own, and the model choices join the models they are about.

ALTER TABLE workspace_audit DROP CONSTRAINT ck_workspace_audit_category;
ALTER TABLE workspace_audit ADD CONSTRAINT ck_workspace_audit_category
    CHECK (category IN ('WORKSPACE', 'WORKFLOW', 'AGENT', 'INTEGRATION', 'MODEL', 'MEMORY', 'OBJECT', 'CHAT'));

-- The entries already written keep their meaning; only where they are shown changes.
UPDATE workspace_audit SET category = 'CHAT'
    WHERE category = 'WORKSPACE' AND message LIKE 'Attached %';

UPDATE workspace_audit SET category = 'MODEL'
    WHERE category = 'WORKSPACE'
      AND (message LIKE 'Companion model %' OR message LIKE 'Transcription model %');
