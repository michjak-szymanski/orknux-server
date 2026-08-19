-- A run that a condition stopped finished without doing everything it could
-- have. It did not fail — the workflow asked a question and acted on the answer
-- — so the status stays COMPLETED and the reason is recorded beside it, rather
-- than a reader having to work it out from which steps stayed pending.
ALTER TABLE workflow_execution
    ADD COLUMN stopped_at_node_key VARCHAR(64);

ALTER TABLE workflow_execution
    ADD COLUMN stopped_reason VARCHAR(500);
