-- What a plugin asks the server to do on its behalf, and what was accepted.
--
-- Kept apart from the permissions beside them because they differ in kind: a
-- permission turns on a language builtin and reaches nothing, while a capability
-- is the server making a call for the plugin. Two lists, granted separately, and
-- shown separately to whoever accepts a plugin. Issue #316.
--
-- Empty for every plugin loaded before this, which is what they all asked for.
ALTER TABLE plugin ADD COLUMN declared_capabilities TEXT NOT NULL DEFAULT '[]';
ALTER TABLE plugin ADD COLUMN accepted_capabilities TEXT NOT NULL DEFAULT '[]';
