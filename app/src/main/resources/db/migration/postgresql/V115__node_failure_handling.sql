-- What a node does when it fails.
--
-- Until now there was one answer for every kind of failure: the step was
-- recorded as failed and the run stopped where it stood. That made a workflow
-- an all-or-nothing thing - the message that could not be posted took the
-- ticket update down with it - and it made "try that again" something a person
-- had to do by hand, for failures that were nothing but a network dropping.
--
-- Two settings on the node, and a third way out of it.

-- Whether this action has a second way out for when it fails.
--
-- Kept on the node rather than read off the edges, because the handle has to
-- exist before anything can be drawn from it, and because switching it off is
-- a decision worth recording - a graph should not quietly lose its fallback
-- because somebody deleted the last edge leaving by it.
ALTER TABLE workflow_node
    ADD COLUMN fallback_enabled BOOLEAN NOT NULL DEFAULT FALSE;

-- How many times in all a run may attempt this action, and how long it leaves a
-- failed attempt alone before the next.
--
-- Attempts rather than retries, so the number saved on the node is the number
-- of times the work is performed at worst. Null is once, which is what every
-- node did until now and what every existing row therefore means.
--
-- The wait is the same between every attempt. A curve is a third setting and a
-- second thing to explain, and nothing here has wanted one.
ALTER TABLE workflow_node
    ADD COLUMN retry_attempts INTEGER,
    ADD COLUMN retry_backoff_seconds INTEGER;

-- The run's own copy of the policy, alongside how much of it has been spent.
--
-- Copied for the same reason the mappings are: a policy edited while a run is
-- between two attempts must not change how many that run gets. And the count
-- is on the row because an attempt and the one after it can be carried by
-- different workers in different processes - Temporal hands the step back as a
-- fresh activity call, which knows nothing of what the last one tried.
ALTER TABLE execution_step
    ADD COLUMN retry_attempts INTEGER,
    ADD COLUMN retry_backoff_seconds INTEGER,
    ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0;

-- FAILURE, as a way out a step can have taken.
--
-- A failed step whose branch says FAILURE is one whose failure the graph had an
-- answer for: the run did not stop there, it went on down the edge drawn for
-- exactly this. Without the distinction a re-run starting further down cannot
-- tell that path from one that never happened, which is the same reason the
-- column exists for YES and NO.
ALTER TABLE execution_step
    DROP CONSTRAINT ck_execution_step_branch;

ALTER TABLE execution_step
    ADD CONSTRAINT ck_execution_step_branch CHECK (branch IS NULL OR branch IN ('YES', 'NO', 'FAILURE'));
