-- What a trigger hands the run it starts.
--
-- A scheduled trigger has nothing to say by itself — the clock carries no data —
-- so without this a workflow started on a schedule is handed only the cron
-- expression, and a function called from it has nothing to work on.
ALTER TABLE workflow_trigger
    ADD COLUMN payload TEXT;
