-- An icon on the definition, so a node drawn from it starts with the right one.
--
-- A node can already be given an icon, but every node had to be given it again:
-- ten nodes running "Send Slack message" meant picking the same icon ten times,
-- and the one somebody forgot is the one that looks like a different action.
--
-- A seed, not a rule. The node keeps its own icon once it has one, so a graph
-- can still say something the catalogue does not — the same way a node's
-- parameters are seeded from an action and owned by the node afterwards.
--
-- Same shape as the node's: a name from the interface's own set, never a file
-- or a URL. Null draws whatever the kind already draws.

ALTER TABLE workflow_action
    ADD COLUMN icon VARCHAR(40);

ALTER TABLE workflow_trigger
    ADD COLUMN icon VARCHAR(40);

ALTER TABLE workflow_condition
    ADD COLUMN icon VARCHAR(40);

ALTER TABLE agent
    ADD COLUMN icon VARCHAR(40);
