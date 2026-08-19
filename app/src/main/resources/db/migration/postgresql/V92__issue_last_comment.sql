-- When an issue was last said something on.
--
-- "Sort by last change" already exists, and it is not this: closing an issue,
-- relabelling it or assigning it all count as changes, so a tracker sorted that
-- way puts the housekeeping at the top and the conversation wherever it lands.
-- What somebody scanning for where the talking is wants is the last time a
-- person wrote something, which nothing recorded.
--
-- Kept on the issue rather than worked out per query. The alternative is
-- ordering by a subquery over the comments, which Spring Data's Sort cannot
-- express - it would mean a separate repository method for every combination of
-- filter and order, and the list already has three orders, two directions, a
-- search and a status.
ALTER TABLE workspace_issue
    ADD COLUMN last_comment_at TIMESTAMPTZ;

-- What the comments already say. Null stays null for an issue nobody has
-- replied to, which is the honest answer rather than falling back to when the
-- issue was filed: sorted by conversation, an issue with no conversation has no
-- place in the order, and it sorts last.
UPDATE workspace_issue i
SET last_comment_at = (
    SELECT max(c.created_at)
    FROM workspace_issue_comment c
    WHERE c.issue_id = i.id
);
