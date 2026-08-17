-- A trigger anything can call: a URL, and the shape of what it must be sent.
--
-- Slack and the clock were the only two ways a workflow could start, so anything
-- else — a build finishing, a form being submitted, another system's own
-- webhook — had nowhere to arrive.
--
-- The path is the second half of the URL and belongs to the installation rather
-- than to a workspace: two workspaces cannot both answer at /api/webhooks/build.
--
-- The object is the contract. A request that does not match it is answered 404,
-- the same as a path nothing listens on: an endpoint that says "not this shape"
-- tells whoever is probing that something is there.

ALTER TABLE workflow_trigger
    ADD COLUMN webhook_path VARCHAR(120),
    ADD COLUMN object_id BIGINT;

CREATE UNIQUE INDEX uk_workflow_trigger_webhook_path
    ON workflow_trigger (webhook_path)
    WHERE webhook_path IS NOT NULL;
