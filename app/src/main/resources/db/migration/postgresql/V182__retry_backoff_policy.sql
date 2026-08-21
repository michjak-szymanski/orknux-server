-- A backoff policy, where there was a wait and a word for one of two curves.
--
-- FIXED and EXPONENTIAL were the two shapes anybody had asked for, and the
-- column was written as a word precisely so a third could be added without a
-- second column contradicting the first. What arrived instead was the request
-- underneath all three: the curve itself, as a number. A multiplier of one is
-- FIXED, two is EXPONENTIAL, and 1.5 is the thing neither word could say - a
-- wait that grows, but not so fast that the fourth attempt is a quarter of an
-- hour after the third.
--
-- So the word goes and the number replaces it, rather than sitting beside it.
-- EXPONENTIAL becomes 2 on the way past, which is what EXPONENTIAL meant; null
-- stays null, and null is one, which is what FIXED meant. Every node written
-- before today therefore waits exactly what it waited yesterday.
--
-- The other three are what a multiplier makes necessary. A ceiling, because a
-- curve without one is a run that disappears for a day over two numbers that
-- did not look like a day. Jitter, because a hundred runs that failed together
-- retry together, and the service they are retrying is the one that failed.
-- And a budget, because "stop after ten minutes whatever you are doing" is the
-- sentence people actually say, and no arrangement of the other five says it:
-- they bound the waiting and the work between them is unbounded.
ALTER TABLE workflow_node
    ADD COLUMN retry_multiplier       DOUBLE PRECISION,
    ADD COLUMN retry_max_wait_seconds INTEGER,
    ADD COLUMN retry_jitter           DOUBLE PRECISION,
    ADD COLUMN retry_budget_seconds   INTEGER;

-- What the word meant, said as the number. Before the column is dropped, so a
-- node that doubled goes on doubling.
UPDATE workflow_node
SET retry_multiplier = 2
WHERE retry_backoff = 'EXPONENTIAL';

ALTER TABLE workflow_node
    DROP CONSTRAINT ck_workflow_node_retry_backoff;

ALTER TABLE workflow_node
    DROP COLUMN retry_backoff;

ALTER TABLE workflow_node
    ADD CONSTRAINT ck_workflow_node_retry_multiplier
        CHECK (retry_multiplier IS NULL OR (retry_multiplier >= 1 AND retry_multiplier <= 10)),
    ADD CONSTRAINT ck_workflow_node_retry_max_wait
        CHECK (retry_max_wait_seconds IS NULL OR (retry_max_wait_seconds >= 1 AND retry_max_wait_seconds <= 3600)),
    ADD CONSTRAINT ck_workflow_node_retry_jitter
        CHECK (retry_jitter IS NULL OR (retry_jitter >= 0 AND retry_jitter <= 1)),
    ADD CONSTRAINT ck_workflow_node_retry_budget
        CHECK (retry_budget_seconds IS NULL OR (retry_budget_seconds >= 1 AND retry_budget_seconds <= 86400));

-- The run's own copy, for the reason it copies the attempts and the wait: a
-- policy edited while a run sits between two attempts must not change the clock
-- that run is already on.
ALTER TABLE execution_step
    ADD COLUMN retry_multiplier       DOUBLE PRECISION,
    ADD COLUMN retry_max_wait_seconds INTEGER,
    ADD COLUMN retry_jitter           DOUBLE PRECISION,
    ADD COLUMN retry_budget_seconds   INTEGER;

UPDATE execution_step
SET retry_multiplier = 2
WHERE retry_backoff = 'EXPONENTIAL';

ALTER TABLE execution_step
    DROP CONSTRAINT ck_execution_step_retry_backoff;

ALTER TABLE execution_step
    DROP COLUMN retry_backoff;

-- When the budget runs out, written down the first time the step is attempted
-- rather than worked out from the attempts.
--
-- A budget is wall clock and the run is not: the work itself takes as long as it
-- takes, an attempt and the one after it can be carried by different workers,
-- and started_at is rewritten by every attempt so it cannot answer when the
-- first one began. So the deadline is recorded once, where both workers and the
-- attempt after next can read the same answer.
ALTER TABLE execution_step
    ADD COLUMN retry_deadline TIMESTAMP WITH TIME ZONE;
