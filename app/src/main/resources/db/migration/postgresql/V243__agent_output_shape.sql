-- An agent node can be held to a shape: one of the workspace's objects, chosen
-- on the node, that the model's answer has to match. Its own column rather than
-- reusing object_id, because that column means "the shape an object node
-- makes", seeds an object node's mapping rows, and is gated to that kind - one
-- column meaning two things is one that will one day mean the wrong one.
--
-- The run's own copy travels onto the step, like every other id a node
-- instances, so a published workflow keeps meaning what it meant.
ALTER TABLE workflow_node ADD COLUMN output_object_id BIGINT;
ALTER TABLE execution_step ADD COLUMN output_object_id BIGINT;
