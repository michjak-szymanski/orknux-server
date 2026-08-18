-- Whether the quick chat may do things, or only look them up.
--
-- It was read-only by construction, which is the right default and the wrong
-- rule to hard-code: a workspace where the same people would start a workflow
-- from the Workflows page anyway loses nothing by letting the panel do it, and
-- one where an answer is wanted but an action is not keeps that.
--
-- Off for every workspace, including those that already chose a model: a
-- capability that appeared by upgrading is one nobody decided to grant.
ALTER TABLE workspace
    ADD COLUMN quick_chat_may_write BOOLEAN NOT NULL DEFAULT FALSE;
