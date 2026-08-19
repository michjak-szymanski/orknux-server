-- A trigger may ask a question before it starts anything.
--
-- Without this the only place to filter is inside the workflow, which means the
-- run has already started, been audited and shown up in the executions list
-- before anything decides it was not wanted. A mention in the wrong channel is
-- then indistinguishable from real work.
--
-- No foreign key action beyond RESTRICT: a condition still being asked by a
-- trigger is not one to delete out from under it.
ALTER TABLE workflow_trigger
    ADD COLUMN condition_id BIGINT REFERENCES workflow_condition (id);
