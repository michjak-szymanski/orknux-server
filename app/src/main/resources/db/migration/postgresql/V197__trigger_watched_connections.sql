-- Which outbound connections a reply trigger watches for replies to.
--
-- A trigger already names a connection, and it is not this one. `connection_id`
-- is the socket: the Slack app whose app-level token this installation receives
-- events over. What a reply trigger also has to know is whose *messages* count
-- as the parent - and that is a bot token, which is a Slack user. A thread reply
-- carries `parent_user_id`, so "a reply to one of ours" is that id measured
-- against the user behind each of these tokens.
--
-- The two are different rows on purpose. One workspace hears everything through
-- one app, and the bots people want answered are usually other apps entirely.
--
-- A table rather than a column because it is a set: one workflow watching two
-- bots is more plausible than a reason to forbid it, and a comma-separated
-- column is a set nobody can join against.
CREATE TABLE workflow_trigger_watch
(
    trigger_id    BIGINT NOT NULL,
    connection_id BIGINT NOT NULL,
    PRIMARY KEY (trigger_id, connection_id),
    CONSTRAINT workflow_trigger_watch_trigger_id_fkey
        FOREIGN KEY (trigger_id) REFERENCES workflow_trigger (id) ON DELETE CASCADE
);

-- No foreign key on connection_id, the same way `workflow_trigger.connection_id`
-- carries none: `workspace_connection` belongs to another module, and a deleted
-- workspace is reported across that boundary rather than cascaded over it.
-- Which leaves this to be read the way the trigger's own column is read - a row
-- naming a connection that has gone is a trigger the form draws as unset.
