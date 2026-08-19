-- Re-running a workflow from one of its steps.
--
-- Until now the only way to run something again was to run all of it. A run
-- that failed at the last node of six had to redo the five that worked, which
-- for anything that sends, charges or writes is not a repeat but a second
-- occurrence - so the honest way to try a fixed node was not to try it at all.
--
-- Starting partway needs two things the record did not keep.

-- Which way out of a condition the run went.
--
-- The engine decided a branch, followed the edges carrying that answer, and
-- kept nothing: the step's row said COMPLETED and no more. A run starting from
-- a node halfway down has to know which edges the earlier run took, or it
-- cannot tell a path that was skipped because the condition went the other way
-- from a path that simply has not happened yet - and would resurrect the branch
-- nobody took.
--
-- Null for every step that is not a condition, and for every condition drawn
-- without branches, which is what it means to answer nothing. Null on every row
-- that already exists too: those runs predate the record, and a re-run that
-- would have to guess refuses instead.
ALTER TABLE execution_step
    ADD COLUMN branch VARCHAR(8);

ALTER TABLE execution_step
    ADD CONSTRAINT ck_execution_step_branch CHECK (branch IS NULL OR branch IN ('YES', 'NO'));

-- Whether this step was copied from an earlier run rather than performed here.
--
-- The steps ahead of the chosen one appear in the new run as what they were:
-- the same status, the same input, the same output, the same times. Leaving
-- them pending would have read as a run that never started, and re-running them
-- is the whole thing being avoided. So they are shown, and marked, because a
-- step that says COMPLETED at 09:14 when the run began at 11:02 is a lie unless
-- something on the row says where it came from.
ALTER TABLE execution_step
    ADD COLUMN carried_over BOOLEAN NOT NULL DEFAULT FALSE;
