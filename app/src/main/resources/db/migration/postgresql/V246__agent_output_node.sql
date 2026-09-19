-- An agent node can save its answer into an object node on the same graph,
-- named by that node's key. The shape column beside it is then derived from
-- the target at every save rather than chosen, so the two cannot disagree.
ALTER TABLE workflow_node ADD COLUMN output_node_key VARCHAR(64);
