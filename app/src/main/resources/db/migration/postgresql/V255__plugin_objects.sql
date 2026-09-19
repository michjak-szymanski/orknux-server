-- The shapes a plugin exports.
--
-- Rows in the table a workspace's own objects already live in, rather than a
-- table of their own. A property points at an object by id, and everything
-- that follows one of those references -- the shape a model is told, the check
-- an answer is held to, the picker a function's return type is chosen from --
-- follows it without asking whose the row is. A second table would mean
-- teaching every one of those about a second kind.
--
-- What separates them is the owner: exactly one of workspace_id and plugin_id
-- is set. A plugin's row is replaced wholesale the next time the plugin is
-- loaded and goes when it is unloaded, which is what the cascade says.
ALTER TABLE workflow_object ALTER COLUMN workspace_id DROP NOT NULL;
ALTER TABLE workflow_object ADD COLUMN plugin_id bigint REFERENCES plugin (id) ON DELETE CASCADE;

CREATE INDEX idx_workflow_object_plugin ON workflow_object (plugin_id);

-- Exactly one owner. Written as a constraint rather than trusted to the code
-- because a row with neither is a shape nobody can reach and a row with both
-- is a shape two things would claim.
ALTER TABLE workflow_object
    ADD CONSTRAINT workflow_object_has_one_owner
        CHECK ((workspace_id IS NULL) <> (plugin_id IS NULL));

-- A plugin's own names are unique within it, the way a workspace's are within
-- a workspace. The existing uk_workflow_object_name stops saying anything the
-- moment workspace_id may be null -- two nulls are distinct to a unique
-- constraint -- so the plugin's half of the rule is written here.
ALTER TABLE workflow_object
    ADD CONSTRAINT uk_workflow_object_plugin_name UNIQUE (plugin_id, name);

-- A reference survives its target being cascaded away, as nothing.
--
-- object_property.ref_object_id had no action at all, which was fine while
-- every path that deletes an object refused first: deleteObject checks what
-- points at the row and says no. A plugin's shapes go by cascade when the
-- plugin is unloaded, and a plugin whose shapes point at each other then
-- deletes rows in an order the constraint blocks -- the plugin cannot be
-- unloaded at all, which is not a rule anybody wrote.
--
-- SET NULL rather than CASCADE: the property is still a field of the shape
-- holding it, and taking the field away because what it pointed at is gone
-- would edit somebody's object on their behalf. Null reads as "points at
-- nothing", which is what happened.
ALTER TABLE object_property DROP CONSTRAINT object_property_ref_object_id_fkey;
ALTER TABLE object_property
    ADD CONSTRAINT object_property_ref_object_id_fkey
        FOREIGN KEY (ref_object_id) REFERENCES workflow_object (id) ON DELETE SET NULL;

-- What the plugin declared, kept beside its functions and its skills.
ALTER TABLE plugin ADD COLUMN declared_objects text NOT NULL DEFAULT '[]';
