-- The other half of admitting a third kind of trigger.
--
-- Two rules stand over this table: one for what a kind has to have, rewritten
-- last time, and this one for which kinds exist at all. Missing it meant a
-- webhook was refused by a list of names rather than by anything about it.
--
-- Checked here as well as in the enum because the database outlives any one
-- process: a row nobody's code would write can still arrive from a script, a
-- restore, or a migration written in a hurry.

ALTER TABLE workflow_trigger
    DROP CONSTRAINT ck_workflow_trigger_type;

ALTER TABLE workflow_trigger
    ADD CONSTRAINT ck_workflow_trigger_type CHECK (
        type IN ('INCOMING_CONNECTION', 'SCHEDULED', 'WEBHOOK')
    );

-- And the same omission one table over.
--
-- The node kinds were listed when the editor had four of them; an object node
-- would have been refused here for the same reason, after the application had
-- accepted it. Data task and publish task are dropped from the list at the same
-- time — nothing ran them, nothing has ever been saved as one, and the editor
-- no longer offers them.

ALTER TABLE workflow_node
    DROP CONSTRAINT ck_workflow_node_kind;

ALTER TABLE workflow_node
    ADD CONSTRAINT ck_workflow_node_kind CHECK (
        kind IN ('TRIGGER', 'AGENT', 'ACTION', 'CONDITION', 'OBJECT')
    );

-- A step records the kind of node it ran, so it is the same list again.
--
-- Left behind, an object node would save and then fail the moment a run reached
-- it — the worst version of this, because the graph would look correct until it
-- was used.

ALTER TABLE execution_step
    DROP CONSTRAINT ck_execution_step_kind;

ALTER TABLE execution_step
    ADD CONSTRAINT ck_execution_step_kind CHECK (
        kind IN ('TRIGGER', 'AGENT', 'ACTION', 'CONDITION', 'OBJECT')
    );
