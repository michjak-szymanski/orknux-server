-- Which run this one was started from.
--
-- A run can now be started again two ways: the whole of it, or from one of its
-- steps carrying what the earlier run produced. Both write a new row, and until
-- now that row said nothing about where it came from - so a run that exists
-- only because somebody pressed re-run looked exactly like one an event caused,
-- and the run it answers was a thing you had to remember rather than follow.
--
-- Null for an ordinary run, which is the great majority of them, and null on
-- every row that already exists: those runs predate the record, and a link that
-- was never kept must not be invented from the timestamps.
--
-- It points at the same table, and gives way rather than holding on: if the
-- earlier run is deleted the link goes, because a link to a run nobody can open
-- is worse than no link at all.
ALTER TABLE workflow_execution
    ADD COLUMN started_from BIGINT;

ALTER TABLE workflow_execution
    ADD CONSTRAINT fk_workflow_execution_started_from
        FOREIGN KEY (started_from) REFERENCES workflow_execution (id) ON DELETE SET NULL;
