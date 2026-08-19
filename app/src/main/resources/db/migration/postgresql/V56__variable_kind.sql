-- Which variables are secret, and which are simply configuration.
--
-- Both are named values a function is handed, but they are not the same thing to
-- look at: a signing secret must not be on screen while somebody shares it, and
-- a channel name or a threshold is only awkward to work with hidden.
--
-- Both are still encrypted at rest. The difference is who may see it and when —
-- a value comes back with the list, a secret comes back only when somebody asks
-- for it and the audit log records that they did.
--
-- Existing rows become secrets, which is the safe direction: a value wrongly
-- treated as a secret is an inconvenience, the other way round is a leak.

ALTER TABLE workspace_variable
    ADD COLUMN kind VARCHAR(16) NOT NULL DEFAULT 'SECRET';

ALTER TABLE workspace_variable
    ADD CONSTRAINT ck_workspace_variable_kind CHECK (kind IN ('VALUE', 'SECRET'));
