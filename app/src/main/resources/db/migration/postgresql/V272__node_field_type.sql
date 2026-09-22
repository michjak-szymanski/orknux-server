-- A field a node writes can say what it holds.

-- An Object node with no saved shape carries fields of its own, and those
-- fields were a name and a value and nothing else. So a number written into one
-- arrived as the string "3", a flag as "true", and an object somebody pasted in
-- as the text of it - and whoever read the node's output downstream got a shape
-- they had to undo before they could use it.

-- The three columns are the ones a saved object's property already has, because
-- this is the same question asked in a different place: what one of it is, what
-- a list of them holds, and which shape it points at where it points at one.
-- Two answers to that would be one too many.

-- Null is untyped, which is what every mapping already is and what most of them
-- stay: an action's parameter is typed by the function it belongs to and a
-- condition's by its own, so only a field somebody names here has this to say.

ALTER TABLE workflow_node_mapping
    ADD COLUMN field_kind VARCHAR(16);

ALTER TABLE workflow_node_mapping
    ADD COLUMN field_element_kind VARCHAR(16);

ALTER TABLE workflow_node_mapping
    ADD COLUMN field_ref_object_id BIGINT;
