-- What a plugin says it offers.
--
-- Pulled from the plugin while it is being uploaded and kept as it answered, so
-- the screen can say what a plugin brings without loading and running it again.
--
-- JSON in a text column rather than columns of its own, because this is the
-- plugin's answer rather than the server's model of it: the rows that make these
-- callable are `workflow_function` rows, and those are created when a plugin's
-- functions are materialised into a workspace. This is the declaration; that is
-- the registration.
--
-- Text rather than JSONB to match the rest of this schema, which has no jsonb
-- anywhere. Nothing queries inside this column.
ALTER TABLE plugin
    ADD COLUMN declared_functions TEXT NOT NULL DEFAULT '[]';
