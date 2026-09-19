-- A third credential on a connection, for the one Slack call a bot token
-- cannot make: search.messages answers only for a user token (xoxp-, with
-- search:read). Stored and referenced exactly like the app-level token, and
-- with the same rule: a copy of its own or a workspace variable, never both.
ALTER TABLE workspace_connection
    ADD COLUMN user_token VARCHAR(4000);

ALTER TABLE workspace_connection
    ADD COLUMN user_token_variable_id BIGINT;

ALTER TABLE workspace_connection
    ADD CONSTRAINT ck_workspace_connection_user_token
        CHECK (user_token_variable_id IS NULL OR user_token IS NULL);
