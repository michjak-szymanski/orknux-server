-- A plugin says which JavaScript it needs, and somebody has to agree to it.
--
-- A plugin embeds its libraries - that is what makes it portable, and it is why a
-- plugin cannot import one the way a function can. The cost is that a bundle
-- written for a browser or for Node expects language features this sandbox does
-- not switch on, so a plugin needing TextDecoder has simply not worked.
--
-- Two columns, because they are two different facts and the whole design turns on
-- keeping them apart. declared_permissions is what the plugin asks for, read out
-- of the plugin itself every time it is loaded. accepted_permissions is what a
-- person agreed to, and it is the only thing that is ever relaxed.
--
-- So an escalation cannot inherit an acceptance. A plugin edited to need more is
-- loaded again, declares more, and what it declares is no longer covered by what
-- was accepted - which is refused, with the new list, until somebody accepts it
-- afresh. Nothing about the old acceptance carries the new permission, because
-- the old acceptance names the permissions it was given for.
--
-- And what was accepted stays readable afterwards, by whoever did not do the
-- accepting: it is on the plugin, beside who accepted it and when, rather than
-- being a decision that happened once in a dialog and left no trace.
ALTER TABLE plugin
    ADD COLUMN declared_permissions TEXT NOT NULL DEFAULT '[]';

ALTER TABLE plugin
    ADD COLUMN accepted_permissions TEXT NOT NULL DEFAULT '[]';

-- Null for a plugin that asks for nothing, which is every plugin loaded before
-- this. There is nothing to accept, so nobody accepted it.
ALTER TABLE plugin
    ADD COLUMN permissions_accepted_at TIMESTAMPTZ;

ALTER TABLE plugin
    ADD COLUMN permissions_accepted_by VARCHAR(120);
