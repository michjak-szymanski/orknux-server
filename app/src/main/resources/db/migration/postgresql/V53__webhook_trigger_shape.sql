-- A webhook is a third shape a trigger can have.
--
-- The rule written when there were two kinds says a trigger is either a
-- connection and an event, or a cron expression — so a webhook, which is
-- neither, was refused by the database after the application had accepted it.
--
-- Rewritten rather than dropped: what makes each kind whole is worth stating
-- once, where nothing can save a half-configured trigger around it. A webhook
-- is whole when it has both halves of what it promises — the path it answers
-- on, and the shape it answers for.

ALTER TABLE workflow_trigger
    DROP CONSTRAINT ck_workflow_trigger_shape;

ALTER TABLE workflow_trigger
    ADD CONSTRAINT ck_workflow_trigger_shape CHECK (
        (type = 'INCOMING_CONNECTION' AND connection_id IS NOT NULL AND action IS NOT NULL)
        OR (type = 'SCHEDULED' AND cron IS NOT NULL)
        OR (type = 'WEBHOOK' AND webhook_path IS NOT NULL AND object_id IS NOT NULL)
    );
