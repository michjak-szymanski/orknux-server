-- Two things about a loaded plugin that nothing recorded before.
--
-- `enabled` is the reversible half of unloading: off keeps the row, the
-- edited functions and every workspace's parameter answers, and offers
-- nothing. Everything already loaded is on, which is what it was.
ALTER TABLE plugin
    ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT TRUE;

-- And where it came from, for a plugin installed from the marketplace: the
-- catalog key and the version at that moment, so a listing and an installed
-- row can be lined up and an update offered. Null for a plugin loaded from a
-- file or a URL of somebody's own, which is every plugin loaded until now.
ALTER TABLE plugin
    ADD COLUMN marketplace_key VARCHAR(64);

ALTER TABLE plugin
    ADD COLUMN marketplace_version VARCHAR(32);
