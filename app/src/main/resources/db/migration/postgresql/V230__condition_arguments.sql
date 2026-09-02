-- What a function condition passes to its function, one row per parameter.
--
-- A condition with no rows here is every condition written before this, and it
-- means what it always meant: the function is handed what the run is carrying,
-- as one argument, then the workspace values it declared. So nothing that
-- worked stops working. Issue #316.
--
-- The same three columns a node's mapping has, because it is the same decision:
-- a written value, or the name of a field the run carries, and which of the two.
CREATE TABLE workflow_condition_argument
(
    condition_id BIGINT       NOT NULL REFERENCES workflow_condition (id) ON DELETE CASCADE,
    position     INTEGER      NOT NULL,
    name         VARCHAR(64)  NOT NULL,
    expression   TEXT         NOT NULL,
    mode         VARCHAR(16)  NOT NULL,
    PRIMARY KEY (condition_id, position)
);
