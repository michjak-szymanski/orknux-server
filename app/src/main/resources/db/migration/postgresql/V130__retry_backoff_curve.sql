-- How the wait between attempts grows.
--
-- Retries arrived with one wait and the same wait before every attempt, and the
-- note beside it said a curve was a third setting nobody had wanted. Somebody
-- has: a model answering 429 is not rate limited for a fixed number of seconds,
-- and asking it again on the same short clock three times over spends the
-- attempts without ever having waited long enough to be let back in. Doubling
-- is the shape that fits - a first retry that is quick because most failures are
-- a blip, and a last one far enough out to have outlasted something real.
--
-- A word rather than a boolean, because the third curve somebody asks for should
-- add a value here and not a second column contradicting the first.
--
-- Null is FIXED, which is what every row written until now means and what every
-- node without an opinion goes on doing.
ALTER TABLE workflow_node
    ADD COLUMN retry_backoff VARCHAR(16);

ALTER TABLE workflow_node
    ADD CONSTRAINT ck_workflow_node_retry_backoff
        CHECK (retry_backoff IS NULL OR retry_backoff IN ('FIXED', 'EXPONENTIAL'));

-- The run's own copy, for the same reason it copies the attempts and the wait:
-- a node switched from fixed to doubling while a run is between two attempts
-- must not change the clock that run is already on.
ALTER TABLE execution_step
    ADD COLUMN retry_backoff VARCHAR(16);

ALTER TABLE execution_step
    ADD CONSTRAINT ck_execution_step_retry_backoff
        CHECK (retry_backoff IS NULL OR retry_backoff IN ('FIXED', 'EXPONENTIAL'));
