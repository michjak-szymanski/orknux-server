-- A wait no longer holds the thread carrying the run. The node is asked, and
-- if it is not ready it says when to come back; whatever is carrying the run --
-- a Temporal timer, or the inline engine's own sleep -- asks it again then.
--
-- So a step has something else it can be doing, and a wait that outlives the
-- worker that started it needs its deadline written down: resumed an hour
-- later, on another worker, it counts from when it first parked.

ALTER TABLE execution_step
    ADD COLUMN wait_until TIMESTAMP WITH TIME ZONE;

ALTER TABLE execution_step
    DROP CONSTRAINT ck_execution_step_status;

ALTER TABLE execution_step
    ADD CONSTRAINT ck_execution_step_status
        CHECK (status IN ('PENDING', 'RUNNING', 'WAITING', 'COMPLETED', 'FAILED', 'SKIPPED'));
