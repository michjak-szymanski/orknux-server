-- When a chat is summarised, how short the summary has to be, and what writes it.
--
-- Null is off, which is every workspace until somebody asks for it: a
-- conversation that outgrows its model's window fails on the next turn today,
-- and the failure names a limit rather than what to do about it. Issue #286.
--
-- The model is its own column rather than "the one the chat uses" because
-- summarising is a cheaper job than answering, done once in a while, and a
-- workspace talking to an expensive model has every reason to summarise with a
-- small one.
ALTER TABLE workspace ADD COLUMN compact_after_tokens INTEGER;
ALTER TABLE workspace ADD COLUMN compaction_summary_tokens INTEGER;
ALTER TABLE workspace ADD COLUMN compaction_model_id BIGINT;
