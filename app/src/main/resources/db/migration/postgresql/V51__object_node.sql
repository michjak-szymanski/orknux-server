-- A node that makes an object.
--
-- Everything a run carries until now is what some step happened to produce, so
-- putting two values together — a ticket out of a Slack message and an agent's
-- answer — meant a function whose whole body was building one object.
--
-- The node names the shape and fills its fields, each one written or read from
-- somewhere else, which is the same choice every other parameter offers.
--
-- Null means the shape is the node's own: the fields it holds are the fields it
-- has. A saved object is the other case, where the workspace already says what
-- the shape is and the node only fills it in.

ALTER TABLE workflow_node
    ADD COLUMN object_id BIGINT;
