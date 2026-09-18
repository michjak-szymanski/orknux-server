-- A plugin's function can be edited from the editor, and the edit is a fact
-- worth recording apart from lastModified: lastModified moves on every plugin
-- reload, and what a reload must NOT do any more is overwrite code somebody
-- changed on purpose. Null means the plugin's declaration still speaks for the
-- row, which is what every plugin function has until somebody edits one.
ALTER TABLE workflow_function ADD COLUMN edited_at TIMESTAMPTZ;
ALTER TABLE workflow_function ADD COLUMN edited_by VARCHAR(120);
