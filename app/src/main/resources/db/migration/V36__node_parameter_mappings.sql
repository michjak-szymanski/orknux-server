-- What a node passes to the thing it points at.
--
-- The catalogue entry says what an action does; the node says with what. A
-- mapping is a property of where the node sits — `{{input.text}}` means "the
-- text that arrived along my edge" — so it cannot belong to an action that may
-- be used by two nodes fed by different triggers.
--
-- The action's own mappings seed a node the first time it points at one, and
-- are never read again: what runs is what the node holds. Editing a node
-- therefore cannot change a definition, which is the point.
CREATE TABLE workflow_node_mapping (
    workflow_node_id BIGINT      NOT NULL REFERENCES workflow_node (id) ON DELETE CASCADE,
    position         INTEGER     NOT NULL,
    -- The parameter being supplied: a function argument, or a placeholder the
    -- action's settings referred to.
    name             VARCHAR(64) NOT NULL,
    -- `{{input.x}}` takes it from upstream; anything else is a literal.
    expression       TEXT        NOT NULL,
    PRIMARY KEY (workflow_node_id, position)
);

-- A run keeps its own copy of what it was told to pass.
--
-- The step already copies which action it ran; the mappings travel with it for
-- the same reason. A workflow edited mid-run, or a run replayed next week, uses
-- what it started with rather than what the editor holds now.
ALTER TABLE execution_step ADD COLUMN mappings TEXT;
