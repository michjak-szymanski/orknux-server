-- The TypeScript a function is written in, beside the JavaScript that runs it.
--
-- Two columns for one function, deliberately. The sandbox runs JavaScript and
-- nothing is going to compile TypeScript at run time, so what runs has to be
-- stored compiled; and what somebody edits has to be stored as they wrote it, or
-- reopening a function would show them the compiler's output instead of their own
-- code. The editor compiles the one into the other and saves both together.
--
-- Nullable, because not every function has TypeScript: the ones a plugin declares
-- are not written here and not editable here, so there is nothing to keep.
ALTER TABLE workflow_function
    ADD COLUMN typescript TEXT;

-- Every function written before this was JavaScript, and JavaScript without
-- annotations is already TypeScript — so the source it has is the source it was
-- written in. Nothing is dropped: opening one shows exactly what was there, and
-- the first save compiles it like any other.
UPDATE workflow_function
SET typescript = source
WHERE scope = 'WORKSPACE';
