-- A workflow's own definition may be half-made, because a graph is a draft.
--
-- The shape checks say a FUNCTION action names a function, an INCOMING_CONNECTION
-- trigger names a connection, and so on. That is right for the workspace's
-- shared list: those are finished things people pick from a list and point
-- several workflows at.
--
-- It is wrong for a definition belonging to one node. That one is filled in
-- where it is used, a field at a time, and the panel holding it writes as it is
-- typed - so "Custom, then Function, and I have not picked the function yet" is
-- an ordinary moment rather than an error. With the check in place nothing
-- could be written at that moment at all, so the graph was saved with the node
-- pointing at nothing and everything typed so far was gone on the next reload.
-- Which is what a person reads as "saving does not work", correctly.
--
-- So the checks now bind only what is shared. An unfinished owned definition is
-- stored as it stands and refused at publish, where the rest of an unfinished
-- graph is refused - see GraphValidator.
ALTER TABLE workflow_action DROP CONSTRAINT IF EXISTS ck_workflow_action_shape;
ALTER TABLE workflow_action ADD CONSTRAINT ck_workflow_action_shape CHECK (
    workflow_id IS NOT NULL OR (
        (type = 'EXECUTE' AND subtype = 'OUTGOING_CONNECTION' AND connection_id IS NOT NULL) OR
        (type = 'EXECUTE' AND subtype = 'SEND_EMAIL' AND connection_id IS NOT NULL) OR
        (type = 'EXECUTE' AND subtype = 'HTTP_REQUEST' AND url IS NOT NULL) OR
        (type = 'EXECUTE' AND subtype = 'FUNCTION' AND function_id IS NOT NULL) OR
        (type = 'WAIT' AND subtype = 'INLINE_CONDITION' AND condition_expression IS NOT NULL) OR
        (type = 'WAIT' AND subtype = 'CONDITION' AND condition_id IS NOT NULL) OR
        (type = 'WAIT' AND subtype = 'TIME' AND duration_seconds IS NOT NULL)
    )
);

ALTER TABLE workflow_condition DROP CONSTRAINT IF EXISTS ck_workflow_condition_shape;
ALTER TABLE workflow_condition ADD CONSTRAINT ck_workflow_condition_shape CHECK (
    workflow_id IS NOT NULL OR (
        (type IN ('ANY_OF', 'ALL_OF') AND property IS NULL AND check_by IS NULL) OR
        (type = 'FUNCTION' AND function_id IS NOT NULL AND property IS NULL AND check_by IS NULL) OR
        (type IN ('SLACK', 'JIRA', 'TIME') AND property IS NOT NULL AND check_by IS NOT NULL)
    )
);

ALTER TABLE workflow_trigger DROP CONSTRAINT IF EXISTS ck_workflow_trigger_shape;
ALTER TABLE workflow_trigger ADD CONSTRAINT ck_workflow_trigger_shape CHECK (
    workflow_id IS NOT NULL OR (
        (type = 'INCOMING_CONNECTION' AND connection_id IS NOT NULL AND action IS NOT NULL) OR
        (type = 'SCHEDULED' AND cron IS NOT NULL) OR
        (type = 'WEBHOOK' AND webhook_path IS NOT NULL AND object_id IS NOT NULL)
    )
);
