-- Which way round a node faces.
--
-- Every node took its input on the left and gave its output on the right, so
-- a graph could only be drawn left to right. That is fine for four nodes and
-- wrong for a screen: a long chain runs off the side while the space below it
-- stays empty, and a branch that would read naturally downwards has to be bent
-- sideways to be drawn at all.
--
-- Kept on the node rather than on the workflow because a graph is rarely all
-- one way: a chain that runs across the top and then turns down the side is
-- exactly what somebody draws when they have room, and forcing one direction
-- for the whole graph would trade one constraint for another.
--
-- Null means the way it always was, so every existing node keeps its shape.
ALTER TABLE workflow_node
    ADD COLUMN orientation VARCHAR(16);
