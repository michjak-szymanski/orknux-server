-- Sending mail: a connection that holds a mail server, and an action that sends
-- through it.
--
-- A mail server is a connection like any other, so it goes in the table that
-- already holds a workspace's credentials rather than in one of its own. What it
-- needs beyond a host is what a mail server asks for and nothing else asks for -
-- a port, a login, an address to send from, and how the session is secured - so
-- those are columns beside the ones Socket Mode added for the same reason.
--
-- The password is `secret`, the column every other credential already lives in,
-- which is what puts it through the same encryption. A second password column
-- would be a second one to remember to encrypt.
--
-- `url` holds the host name for this type. It is the odd one out - every other
-- connection points at an HTTP endpoint - but the alternative was a second
-- address column that is null for every row that is not a mail server, and a
-- connection has exactly one place it points at whatever the protocol is.

ALTER TABLE connection
    DROP CONSTRAINT ck_connection_type;
ALTER TABLE connection
    ADD CONSTRAINT ck_connection_type
        CHECK (type IN ('SLACK', 'SLACK_SOCKET_MODE', 'GITHUB', 'JIRA', 'TEAMS', 'SMTP', 'WEBHOOK'));

ALTER TABLE workspace_connection
    DROP CONSTRAINT ck_workspace_connection_type;
ALTER TABLE workspace_connection
    ADD CONSTRAINT ck_workspace_connection_type
        CHECK (type IN ('SLACK', 'SLACK_SOCKET_MODE', 'GITHUB', 'JIRA', 'TEAMS', 'SMTP', 'WEBHOOK'));

ALTER TABLE workspace_connection
    -- Null takes the port that goes with the security below, since a workspace
    -- that picked STARTTLS means 587 nearly every time.
    ADD COLUMN smtp_port INTEGER,
    -- 320 is the longest an address can be: 64 before the @ and 255 after it.
    ADD COLUMN smtp_username VARCHAR(320),
    ADD COLUMN smtp_from VARCHAR(320),
    -- Defaulted rather than nullable: every connection has an answer to how its
    -- session is secured, and the safe one is the answer for a row that has
    -- never been asked.
    ADD COLUMN smtp_security VARCHAR(16) NOT NULL DEFAULT 'STARTTLS';

ALTER TABLE workspace_connection
    ADD CONSTRAINT ck_workspace_connection_smtp_security
        CHECK (smtp_security IN ('NONE', 'STARTTLS', 'TLS'));

-- What one mail says, seeded onto the nodes drawn from the action. The body is
-- `content`, which is already what a send says; the rest is a mail's own.
ALTER TABLE workflow_action
    -- Addresses separated by commas, as they are typed into a mail client. Read
    -- and written whole, so a child table would only add a join.
    ADD COLUMN email_to VARCHAR(1000),
    ADD COLUMN email_cc VARCHAR(1000),
    ADD COLUMN email_subject VARCHAR(500),
    ADD COLUMN email_reply_to VARCHAR(320);

ALTER TABLE workflow_action
    DROP CONSTRAINT ck_workflow_action_subtype;
ALTER TABLE workflow_action
    ADD CONSTRAINT ck_workflow_action_subtype
        CHECK (subtype IN ('OUTGOING_CONNECTION', 'SEND_EMAIL', 'HTTP_REQUEST', 'FUNCTION',
                           'INLINE_CONDITION', 'CONDITION', 'TIME'));

-- A mail action needs a connection to send through, and nothing else: who it
-- goes to and what it says are the node's, which is why neither is required
-- here. An action with no recipient anywhere reports that it sent nothing.
ALTER TABLE workflow_action
    DROP CONSTRAINT ck_workflow_action_shape;
ALTER TABLE workflow_action
    ADD CONSTRAINT ck_workflow_action_shape CHECK (
        (type = 'EXECUTE' AND subtype = 'OUTGOING_CONNECTION' AND connection_id IS NOT NULL)
            OR (type = 'EXECUTE' AND subtype = 'SEND_EMAIL' AND connection_id IS NOT NULL)
            OR (type = 'EXECUTE' AND subtype = 'HTTP_REQUEST' AND url IS NOT NULL)
            OR (type = 'EXECUTE' AND subtype = 'FUNCTION' AND function_id IS NOT NULL)
            OR (type = 'WAIT' AND subtype = 'INLINE_CONDITION' AND condition_expression IS NOT NULL)
            OR (type = 'WAIT' AND subtype = 'CONDITION' AND condition_id IS NOT NULL)
            OR (type = 'WAIT' AND subtype = 'TIME' AND duration_seconds IS NOT NULL)
        );
