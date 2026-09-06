-- Whether a workspace's chats show when each message was sent.
--
-- The time was stored all along - SPRING_AI_CHAT_MEMORY has carried a
-- timestamp per line since V31 - and nothing read it back. Off by default,
-- because a visual change to every chat is not something an upgrade should
-- decide; a workspace that wants the dates says so on its settings page.
ALTER TABLE workspace
    ADD COLUMN chat_show_timestamps BOOLEAN NOT NULL DEFAULT FALSE;
