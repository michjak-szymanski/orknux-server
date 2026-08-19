-- Which model answers the quick chat.
--
-- The panel beside the interface is not the Chat page: it answers about this
-- installation, in one or two sentences, while somebody is in the middle of
-- something else. That is a different job from the conversation a workspace
-- holds with its agents, and usually a smaller and cheaper model.
--
-- Null means the button is not offered at all, which is the right default for an
-- installation that has not chosen one — better than a button that always fails.
ALTER TABLE workspace
    ADD COLUMN quick_chat_model_id BIGINT REFERENCES llm_model (id) ON DELETE SET NULL;
