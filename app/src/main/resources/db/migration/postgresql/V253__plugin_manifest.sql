-- What a plugin says about itself, from the plugin.json beside it.
--
-- The name was taken from the filename, which is a fact about how a file was
-- saved rather than about the plugin: "slack" and "slack.min" and
-- "slack (2)" are one plugin. A manifest says what it is called, what it is
-- for in a line, and who wrote it -- so a loaded plugin can be read the way
-- a catalog listing is, whether it came from the marketplace, a zip or a URL.
--
-- Null for a plugin loaded before this, and for one that ships no manifest:
-- both are ordinary, and the screen falls back to what it has.
ALTER TABLE plugin
    ADD COLUMN summary TEXT;

ALTER TABLE plugin
    ADD COLUMN author VARCHAR(200);

-- What the plugin calls its own version, which is not what the marketplace
-- offered it as: the two are usually equal and mean different things, so the
-- update comparison goes on using marketplace_version.
ALTER TABLE plugin
    ADD COLUMN version VARCHAR(32);
