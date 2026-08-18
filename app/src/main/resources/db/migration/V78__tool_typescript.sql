-- The TypeScript a tool is written in, beside the JavaScript that runs it.
--
-- The same pair a function keeps, for the same reason: the sandbox runs
-- JavaScript and nothing compiles TypeScript at run time, so what runs is stored
-- compiled — and what somebody edits is stored as they wrote it, or reopening a
-- tool would show them the compiler's output rather than their own code.
--
-- Tools are always written in the workspace, unlike functions, which a plugin
-- may declare and which therefore have no TypeScript to keep. So this is NOT
-- NULL: every tool has both halves, and a tool that lost one would be a tool
-- whose editor and sandbox disagree.
ALTER TABLE agent_tool
    ADD COLUMN typescript TEXT;

-- Every tool written before this was JavaScript, and JavaScript without
-- annotations is already TypeScript, so the source it has is the source it was
-- written in. Nothing is lost: opening one shows exactly what was there, and the
-- first save compiles it like any other.
UPDATE agent_tool
SET typescript = source
WHERE typescript IS NULL;

ALTER TABLE agent_tool
    ALTER COLUMN typescript SET NOT NULL;
