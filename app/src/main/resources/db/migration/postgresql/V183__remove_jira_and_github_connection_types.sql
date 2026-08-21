-- The JIRA and GITHUB connection types go.

-- Neither was ever implemented. The connector branches on exactly two types:
-- SMTP, which is a mail server rather than an HTTP endpoint, and SLACK, which
-- has its own URL, its own auth type and its own probe. Every other type falls
-- through the `else` in all of those decisions, so a JIRA connection and a
-- GITHUB connection were already being handled as plain HTTP endpoints with a
-- URL, an auth type and headers - the same handling WEBHOOK exists to describe.
-- Nothing anywhere calls a Jira API or a GitHub API through a connection.
--
-- Unlike TEAMS, which V160 removed for the same reason, these two were offered:
-- the Add Connection form listed them, and the connection settings page wrote a
-- credential hint for each - "an API token from your Atlassian account", "a
-- personal access token". So the product invited somebody to configure a Jira
-- connection, tested it by GETting the URL they gave, reported it Connected, and
-- then had nothing that could ever use it. That is the thing being removed:
-- an offer with nothing behind it.
--
-- GITHUB is here rather than in a later migration of its own because it is the
-- same finding. Sweeping the enum for the third dead value found the fourth
-- standing beside it, in the same list, offered by the same form, implemented by
-- the same nothing.
--
-- The rows are converted rather than deleted or refused, for the reasons V160
-- gave for TEAMS, and to the same place.
--
-- Converted, because WEBHOOK is by its own definition "anything that is just an
-- HTTP endpoint, until it earns a type of its own", and neither of these ever
-- earned one. A converted row keeps its URL, its override, its credential and
-- its headers and goes on behaving exactly as it did before this ran, so the
-- conversion changes the name of the type and nothing else.
--
-- Not deleted, because the row holds a URL, a secret and headers that somebody
-- entered on purpose, and because connection rows are pointed at:
-- workspace_connection.connection_id references connection ON DELETE SET NULL,
-- so dropping a default would quietly orphan every workspace inheriting it.
--
-- Not refused, because a migration that aborts startup over a type that never
-- did anything is a worse failure than the dead enum value it was meant to
-- remove.
--
-- WEBHOOK is what these rows land on because it is the type that exists at this
-- point in the history. V184 renames it to HTTP, which is what it always was,
-- and carries these rows across with the rest.

UPDATE connection
SET type = 'WEBHOOK'
WHERE type IN ('JIRA', 'GITHUB');

UPDATE workspace_connection
SET type = 'WEBHOOK'
WHERE type IN ('JIRA', 'GITHUB');

-- Narrowed only after the rows have moved; a CHECK is validated against what is
-- already in the table.
ALTER TABLE connection
    DROP CONSTRAINT ck_connection_type;
ALTER TABLE connection
    ADD CONSTRAINT ck_connection_type
        CHECK (type IN ('SLACK', 'SMTP', 'WEBHOOK'));

ALTER TABLE workspace_connection
    DROP CONSTRAINT ck_workspace_connection_type;
ALTER TABLE workspace_connection
    ADD CONSTRAINT ck_workspace_connection_type
        CHECK (type IN ('SLACK', 'SMTP', 'WEBHOOK'));
