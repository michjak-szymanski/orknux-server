-- The library files a plugin ships with, one row per file. A plugin used to
-- be exactly one source; now it may declare libraries() - relative paths -
-- and arrive as a zip or be fetched from a URL, with the files stored here
-- beside it. Position is the evaluation order the import graph settled on.
-- The rows go with the plugin, which is the whole of their lifetime.
CREATE TABLE plugin_library (
    id        BIGSERIAL PRIMARY KEY,
    plugin_id BIGINT       NOT NULL REFERENCES plugin (id) ON DELETE CASCADE,
    position  INTEGER      NOT NULL,
    path      VARCHAR(200) NOT NULL,
    source    TEXT         NOT NULL,
    CONSTRAINT uk_plugin_library_path UNIQUE (plugin_id, path)
);
