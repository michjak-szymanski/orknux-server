-- What an agent that sets no share of its own is given, per workspace.
--
-- V186 put the share on the agent, which is where an exception belongs: what a
-- session should carry depends on what that agent's tools give back, and an
-- agent reading whole files wants a different answer from one reading issue
-- lists. What it is not is where a policy belongs. An installation that has
-- decided its agents should remember more than the built-in allowance had to
-- say so once per agent, and again on every agent created afterwards, and the
-- decision was recoverable only by reading every row.
--
-- So one more column, on the row the agents already belong to, and the
-- resolution is three steps: the agent's own share, then this, then the
-- built-in allowance. Null on every existing row, and null means "not
-- decided" - a workspace that leaves it alone behaves exactly as it did
-- yesterday, and an agent that sets its own share never consults it.
--
-- Still a percentage rather than a count of tokens, and here the argument is
-- stronger than it was on the agent: a workspace runs several models at once
-- whose windows differ by an order of magnitude, so a share is the only unit
-- that can be stated once and mean something against all of them.
ALTER TABLE workspace
    ADD COLUMN default_memory_share INTEGER;

-- The same bounds as the agent's share, and only those.
--
-- Half the window is the ceiling because above it there is nothing left for the
-- instructions, the tool declarations, the question and the answer - true of
-- every model, so it can be checked here. The narrower refusals cannot be: a
-- window too small to carry one exchange, or a model that reserves most of its
-- window for its answer, are facts about one model, and this default is
-- deliberately not tied to one. They are checked in SessionMemoryBudgets, at
-- the point the budget is worked out, against the model the agent really uses.
ALTER TABLE workspace
    ADD CONSTRAINT ck_workspace_default_memory_share
        CHECK (default_memory_share IS NULL OR (default_memory_share >= 1 AND default_memory_share <= 50));
