-- The bell learns to ring for something that is not an issue.
--
-- A task that stops for permission has to reach the person who started it and
-- the people watching it, and there is already one place where an event becomes
-- news for somebody: the desk that writes this table. Adding a second one would
-- have given the bell and orknux_news two records of what happened, and the one
-- nobody was looking at would have been the one that was right.
--
-- So the subject of a news item widens from "an issue" to "an issue or a task".
-- The table keeps its name: it is what the reader cursor, the audience index and
-- every row already written point at, and renaming it would be a migration
-- rather than a widening.
ALTER TABLE issue_news ALTER COLUMN issue_id DROP NOT NULL;
ALTER TABLE issue_news ALTER COLUMN issue_number DROP NOT NULL;
ALTER TABLE issue_news ALTER COLUMN issue_title DROP NOT NULL;

/*
 * The task, and its title copied for the same reason the issue's number is:
 * a bell draws a line of text and must not need a second query to do it.
 *
 * CASCADE, unlike the issue's. An issue that is deleted takes its news with it
 * and a task is no different - the news says "this task is waiting for you",
 * and once the task is gone there is nothing to be waiting for.
 */
ALTER TABLE issue_news ADD COLUMN task_id BIGINT REFERENCES task (id) ON DELETE CASCADE;
ALTER TABLE issue_news ADD COLUMN task_title VARCHAR(200);
