-- A chat may be with one of the workspace's agents rather than with a bare model.
--
-- The model stays on the row as well: an agent supplies one, but the chat keeps
-- what it actually used, so history remains readable after the agent is edited
-- or deleted. No cascade for the same reason — deleting an agent must not delete
-- the conversations somebody had with it.
ALTER TABLE chat_session ADD COLUMN agent_id BIGINT REFERENCES agent (id) ON DELETE SET NULL;
