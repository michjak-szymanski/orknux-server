-- What the canvas draws on a node.
--
-- A graph is read at a glance, and every node currently looks the same but for
-- its accent bar. An icon is the difference between counting edges and seeing
-- what a workflow does.
--
-- A name from the interface's own set, not a file or a URL: a node that draws
-- whatever was pasted is a node that can draw nothing, or something enormous.
-- Null keeps the plain node the kind already gives.

ALTER TABLE workflow_node
    ADD COLUMN icon VARCHAR(40);
