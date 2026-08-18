-- When a comment was last changed, or null if it never was.
--
-- Nullable rather than defaulted to the creation time: "edited" is a thing a
-- reader is told, and a comment nobody has touched should not carry a claim
-- that it was. What the column holds is exactly what the word means.
ALTER TABLE workspace_issue_comment
    ADD COLUMN edited_at TIMESTAMPTZ;
