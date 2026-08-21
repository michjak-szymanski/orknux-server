-- The other half of what V25 said it had already done.
--
-- V25 stopped the foreign key nulling a condition's function, and its comment
-- claimed an action that calls one was already safe. It was not. An action
-- points at both a function and a condition, both were ON DELETE SET NULL, and
-- ck_workflow_action_shape says a FUNCTION action has a function and a
-- CONDITION wait has a condition. So the null the foreign key wrote was a row
-- the check refused, and the delete failed rather than the reference.
--
-- Deleting a workspace is where it showed. The cascade takes the workspace's
-- functions and conditions away, the foreign key nulls the columns on actions
-- that are themselves about to go, and the check fires on a row nobody was
-- keeping:
--
--     CHECK constraint failed: ck_workflow_action_shape
--     [delete from workspace where id=?]
--
-- and the workspace survived. On Postgres the check happens to be deferred to
-- the end of the statement, by which time the action row is gone, so only
-- SQLite - which orknux-one runs - ever said it out loud. The schema was wrong
-- on both.
--
-- Nulling was never the behaviour anyway. Deleting a function or a condition
-- something still uses is refused with a sentence naming what is in the way,
-- in FunctionAPI and ConditionAPI, the way it is for agents, triggers, tools
-- and skills. This makes the foreign key agree with that instead of quietly
-- offering a second answer nothing asks for.

ALTER TABLE workflow_action
    DROP CONSTRAINT workflow_action_function_id_fkey;

ALTER TABLE workflow_action
    ADD CONSTRAINT workflow_action_function_id_fkey
        FOREIGN KEY (function_id) REFERENCES workflow_function (id);

ALTER TABLE workflow_action
    DROP CONSTRAINT workflow_action_condition_id_fkey;

ALTER TABLE workflow_action
    ADD CONSTRAINT workflow_action_condition_id_fkey
        FOREIGN KEY (condition_id) REFERENCES workflow_condition (id);
