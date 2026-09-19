-- The instruction sets a plugin brings.
--
-- A third surface beside its functions and its tools, and the one that does
-- not run: markdown an agent reads before doing something. Kept as JSON on the
-- plugin row like the other two, replaced wholesale on every load, because a
-- plugin's skill is the plugin's -- an edit made here would be lost the next
-- time the plugin is loaded, so there is nowhere to make one.
ALTER TABLE plugin ADD COLUMN declared_skills text NOT NULL DEFAULT '[]';
