-- The id a plugin gives itself.
--
-- Until now a plugin was identified by its filename, which made renaming a file
-- into loading a second plugin, and made the prefix on its function names an
-- accident of whatever the file was called. A plugin now says what it is called,
-- and that answer is its identity: uploading the same id again replaces what is
-- loaded, whatever the file is named.
--
-- It is also the namespace for everything the plugin declares. A function arrives
-- as `key_name`, which is why the length is bounded well below the 120 characters
-- a function name has: 32 here plus an underscore plus a 64-character function
-- name still fits.
ALTER TABLE plugin
    ADD COLUMN plugin_key VARCHAR(32);

-- Anything already loaded was identified by name, and names were unique, so that
-- is the id it had in all but words.
UPDATE plugin SET plugin_key = name WHERE plugin_key IS NULL;

ALTER TABLE plugin
    ALTER COLUMN plugin_key SET NOT NULL;

-- One plugin per id. This replaces the name as the thing that has to be unique;
-- two files may now describe the same plugin, and the second replaces the first.
CREATE UNIQUE INDEX uk_plugin_key ON plugin (plugin_key);

DROP INDEX idx_plugin_name;
