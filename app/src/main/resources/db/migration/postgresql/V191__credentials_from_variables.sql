-- Every stored credential may read a workspace variable secret, per field.
--
-- V188 gave one field this: a model provider's key. That was enough to look
-- right, because a provider has exactly one secret column - `secret` serves the
-- API key or the Entra client secret and `auth_method` decides which - so
-- "this provider reads a workspace secret" and "this field reads a workspace
-- secret" were the same sentence.
--
-- Nothing else is like that. A Slack connection holds two credentials, the bot
-- token in workspace_connection.secret and the app-level token in
-- workspace_connection.app_token, and a single switch above both cannot express
-- "the bot token is a workspace secret, the app token is its own". So the unit
-- is a field: one reference column beside each credential column, and each
-- field answers for itself.
--
-- The columns added here are the ones a workspace owns. shell.private_key,
-- shell.key_passphrase and proxy_rule.password carry the same converter and are
-- deliberately left out: those tables are installation-wide, a variable belongs
-- to a workspace, and pointing an administrator's SSH key at one team's secret
-- would put an installation credential in that team's hands. That wants a
-- decision about where an installation keeps a secret, not a column here.
--
-- By id, for the reason V188 gave at length: a name is not an identity, and a
-- rename or a move between catalogs would silently strand everything holding
-- one - which is what #170 and #228 were. An id survives both, so the only
-- operation left to guard is deletion, and VariableAPI refuses that while
-- anything reads it, naming what does.
--
-- No foreign key, and its absence is deliberate: workspace_variable is the
-- application's table and these are the connection module's, and module tables
-- carry no keys across that boundary. The referential guarantee is in code, and
-- the code expects it can still come apart - a restore of one table without the
-- other, a hand-edited database - so a dangling reference is reported as one on
-- the card rather than read as a field nobody configured.

ALTER TABLE workspace_connection
    ADD COLUMN secret_variable_id BIGINT;

ALTER TABLE workspace_connection
    ADD COLUMN app_token_variable_id BIGINT;

ALTER TABLE mcp_server
    ADD COLUMN secret_variable_id BIGINT;

-- What is enforced is the part local to each table: the two kinds of credential
-- are exclusive, per field. A field told to read a variable while still holding
-- an old copy would be a credential kept past the moment somebody decided to
-- stop keeping it, and it would be unclear which one a call used.
ALTER TABLE workspace_connection
    ADD CONSTRAINT ck_workspace_connection_secret
        CHECK (secret_variable_id IS NULL OR secret IS NULL);

ALTER TABLE workspace_connection
    ADD CONSTRAINT ck_workspace_connection_app_token
        CHECK (app_token_variable_id IS NULL OR app_token IS NULL);

ALTER TABLE mcp_server
    ADD CONSTRAINT ck_mcp_server_secret
        CHECK (secret_variable_id IS NULL OR secret IS NULL);
