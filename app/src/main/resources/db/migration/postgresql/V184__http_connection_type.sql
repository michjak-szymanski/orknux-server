-- The WEBHOOK connection type is renamed to HTTP.

-- One word named two opposite directions.
--
-- A *trigger* of type WEBHOOK is incoming: a path this installation exposes at
-- /api/webhooks/..., which somebody out there calls to start a run. That one is
-- a webhook and keeps the name.
--
-- A *connection* of type WEBHOOK was the opposite: a URL this installation
-- POSTs to. Nothing about sending a request cares whether the receiver calls
-- what it is listening on a webhook - the connector opens the URL, sends the
-- request with whatever auth type and headers the connection carries, and reads
-- the response. That is an HTTP endpoint, and naming it after one thing the far
-- end might happen to be left the same word meaning "we listen" in one screen
-- and "we call out" in the next.
--
-- So it is renamed rather than removed. It is the generic outbound target -
-- every action that posts to an arbitrary URL goes through one, and after V183
-- it is the only type left that is neither Slack nor a mail server - so removing
-- it would take the working half of the product with it. And it is one type, not
-- two: no distinction is being invented here between a "webhook" endpoint and
-- some other endpoint, because there was never one to make.
--
-- Every row moves. A WEBHOOK connection and an HTTP connection are the same row
-- under two spellings: same URL, same override, same auth type, same secret,
-- same headers, same probe. Nothing branches on the type for either name, so
-- there is no behaviour on either side of this to preserve or to change - which
-- is what makes a rename safe where a removal would not be.
--
-- Rows that arrived as TEAMS (V160) or as JIRA and GITHUB (V183) are already
-- WEBHOOK by now and cross with the rest, which is the point of having landed
-- them on the type that survives rather than on one that was about to move.

-- The order here is the reverse of V160's and V183's, and it has to be.
--
-- Those two moved rows onto a value the CHECK already allowed and narrowed it
-- afterwards, so the constraint could stand while the rows moved. A rename has
-- nowhere to move the rows to: 'HTTP' is not a value the standing constraint
-- permits, so the first UPDATE would be refused by the very constraint this
-- migration exists to rewrite. The constraint therefore comes off first, the
-- rows move while nothing is watching, and the new one is added over a table
-- that already reads the way it describes.

ALTER TABLE connection
    DROP CONSTRAINT ck_connection_type;
ALTER TABLE workspace_connection
    DROP CONSTRAINT ck_workspace_connection_type;

UPDATE connection
SET type = 'HTTP'
WHERE type = 'WEBHOOK';

UPDATE workspace_connection
SET type = 'HTTP'
WHERE type = 'WEBHOOK';

ALTER TABLE connection
    ADD CONSTRAINT ck_connection_type
        CHECK (type IN ('SLACK', 'SMTP', 'HTTP'));

ALTER TABLE workspace_connection
    ADD CONSTRAINT ck_workspace_connection_type
        CHECK (type IN ('SLACK', 'SMTP', 'HTTP'));
