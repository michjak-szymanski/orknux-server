-- How many times a task in this workspace may ask its model.
--
-- The number was a setting on the installation and nowhere else: an
-- environment variable, read at start-up, changed by restarting the server.
-- Which is the wrong shape for it twice over. It is the number somebody wants
-- to move while watching a task stop halfway through a piece of work - the one
-- moment a restart of everything is least welcome - and it is a judgement about
-- the work a workspace does rather than about the installation. A workspace
-- running overnight research and one answering questions in a chat have no
-- reason to agree on it.
--
-- Null is every workspace as it stands today, and means the workspace has
-- decided nothing: the installation's own number is used, exactly as before.
-- So this migration changes nothing about how anything runs until somebody
-- fills it in, which is what a column added under a running product should do.
--
-- Read when a task is created and copied onto the task's row, the same as the
-- working time beside it. Raising it gives the next task more; it does not
-- extend one already going, and lowering it does not kill one.
ALTER TABLE workspace
    ADD COLUMN task_max_turns INTEGER;

-- The bounds the screen offers, held by the database as well.
--
-- One turn is a real thing to want: it is how somebody tries a prompt without
-- paying for a loop. Two hundred is there because this is the count that bounds
-- the bill, and a number typed with one digit too many is the mistake it exists
-- to catch.
ALTER TABLE workspace
    ADD CONSTRAINT ck_workspace_task_max_turns
        CHECK (task_max_turns IS NULL OR (task_max_turns BETWEEN 1 AND 200));
