-- The TEAMS connection type goes.
--
-- It was never implemented. Nothing in the connector ever branched on it: the
-- only types that get their own handling are SMTP, which is a mail server rather
-- than an HTTP endpoint, and SLACK, which has its own probe. A TEAMS connection
-- fell through the `else` in every one of those decisions, so it was already
-- being treated as a plain HTTP endpoint with a URL, an auth type and headers.
-- The interface never offered it either, so the only way to have one is to have
-- named the type through the API.
--
-- The rows are therefore converted rather than deleted or refused.
--
-- Converted, because WEBHOOK is by its own definition "anything that is just an
-- HTTP endpoint, until it earns a type of its own", and TEAMS never earned one.
-- A TEAMS row becomes a WEBHOOK row and goes on behaving exactly as it did
-- before this ran - same URL, same credential, same headers, same probe - so
-- the conversion changes the name of the type and nothing else.
--
-- Not deleted, because the row holds a URL, a secret and headers that somebody
-- entered on purpose, and because connection rows are pointed at: dropping one
-- would set workspace_connection.connection_id to null under any workspace
-- inheriting it, quietly breaking a connection that still works.
--
-- Not refused, because a migration that aborts startup over a type nobody could
-- select is a worse failure than the dead enum value it was meant to remove.

UPDATE connection
SET type = 'WEBHOOK'
WHERE type = 'TEAMS';

UPDATE workspace_connection
SET type = 'WEBHOOK'
WHERE type = 'TEAMS';

-- Narrowed only after the rows have moved; a CHECK is validated against what is
-- already in the table.
ALTER TABLE connection
    DROP CONSTRAINT ck_connection_type;
ALTER TABLE connection
    ADD CONSTRAINT ck_connection_type
        CHECK (type IN ('SLACK', 'GITHUB', 'JIRA', 'SMTP', 'WEBHOOK'));

ALTER TABLE workspace_connection
    DROP CONSTRAINT ck_workspace_connection_type;
ALTER TABLE workspace_connection
    ADD CONSTRAINT ck_workspace_connection_type
        CHECK (type IN ('SLACK', 'GITHUB', 'JIRA', 'SMTP', 'WEBHOOK'));
