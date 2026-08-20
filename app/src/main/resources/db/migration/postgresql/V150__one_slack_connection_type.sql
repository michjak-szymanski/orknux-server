-- One Slack connection type, not two.
--
-- SLACK and SLACK_SOCKET_MODE were never two things. The listener fetches both
-- and opens a websocket for whichever holds a bot token and an app-level token,
-- so "outgoing only" listened the moment it was given an app token, and a person
-- adding a connection had to choose between two names for the same integration.
-- What is actually optional is the app-level token: with one the connection
-- listens as well as sends, without one it only sends.
--
-- So the rows move rather than the behaviour. A SLACK_SOCKET_MODE row becomes a
-- SLACK row with both its tokens where they already were, which is a connection
-- that goes on listening exactly as it did before this ran.

UPDATE connection
SET type = 'SLACK'
WHERE type = 'SLACK_SOCKET_MODE';

UPDATE workspace_connection
SET type = 'SLACK'
WHERE type = 'SLACK_SOCKET_MODE';

-- Narrowed only after the rows have moved; a CHECK is validated against what is
-- already in the table.
ALTER TABLE connection
    DROP CONSTRAINT ck_connection_type;
ALTER TABLE connection
    ADD CONSTRAINT ck_connection_type
        CHECK (type IN ('SLACK', 'GITHUB', 'JIRA', 'TEAMS', 'SMTP', 'WEBHOOK'));

ALTER TABLE workspace_connection
    DROP CONSTRAINT ck_workspace_connection_type;
ALTER TABLE workspace_connection
    ADD CONSTRAINT ck_workspace_connection_type
        CHECK (type IN ('SLACK', 'GITHUB', 'JIRA', 'TEAMS', 'SMTP', 'WEBHOOK'));

-- Slack has one Web API base and one way of authenticating, so the form stopped
-- asking for either. The rows that were typed before it stopped are set to the
-- same answers here, so that a connection created by hand and one created by the
-- form do not differ in what a probe calls or how it authenticates.
--
-- url_override is left alone: it is nobody's default, and a workspace that set
-- one pointed at something on purpose.
UPDATE workspace_connection
SET url       = 'https://slack.com/api',
    auth_type = 'BEARER_TOKEN'
WHERE type = 'SLACK';

UPDATE connection
SET url = 'https://slack.com/api'
WHERE type = 'SLACK';
