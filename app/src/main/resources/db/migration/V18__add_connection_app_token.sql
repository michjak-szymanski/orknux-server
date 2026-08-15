-- Slack's Socket Mode opens the websocket with an app-level token (xapp-...),
-- which is a different credential from the bot token used to call the Web API.
-- Both belong to the same connection, so the second one gets a column of its own
-- rather than being squeezed into `secret`.
ALTER TABLE team_connection
    ADD COLUMN app_token VARCHAR(1000);
