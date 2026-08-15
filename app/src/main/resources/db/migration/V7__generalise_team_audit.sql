-- The audit log now covers workflow and agent activity as well as team changes,
-- so entries carry a category and a rendered message.
ALTER TABLE team_audit
    ADD COLUMN category VARCHAR(16),
    ADD COLUMN message  VARCHAR(500);

UPDATE team_audit
SET category = 'TEAM',
    message  = CASE operation_type
                   WHEN 'ADD' THEN 'Team ' || COALESCE(new_team_name, '') || ' created'
                   WHEN 'RENAME' THEN 'Team ' || COALESCE(old_team_name, '') ||
                                      ' renamed to ' || COALESCE(new_team_name, '')
                   WHEN 'REMOVE' THEN 'Team ' || COALESCE(old_team_name, '') || ' deleted'
                   ELSE 'Team updated'
                   END
WHERE category IS NULL;

ALTER TABLE team_audit
    ALTER COLUMN category SET NOT NULL,
    ALTER COLUMN message SET NOT NULL;

-- Only team lifecycle entries carry an operation type and the names either side.
ALTER TABLE team_audit
    ALTER COLUMN operation_type DROP NOT NULL;

ALTER TABLE team_audit
    ADD CONSTRAINT ck_team_audit_category CHECK (category IN ('TEAM', 'WORKFLOW', 'AGENT'));

CREATE INDEX idx_team_audit_category ON team_audit (category);
