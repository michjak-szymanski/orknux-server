-- A parameter is either a value or a reference, and says which.
--
-- It used to be one text box holding either a literal or `{{input.x}}`, and the
-- difference between the two was a syntax you had to know and could get subtly
-- wrong. `{{llmResult}}` — an output named `llmResult`, referred to the obvious
-- way — was not a placeholder at all, so it was sent as those characters, into
-- Slack, with nothing reporting a problem.
--
-- Now the mode is recorded. A value is used as written. A reference names a
-- field the run is carrying, and the node that produced it, which is what lets
-- the editor draw the connection and notice when its source has gone.

ALTER TABLE workflow_node_mapping
    ADD COLUMN mode VARCHAR(16) NOT NULL DEFAULT 'VALUE';

ALTER TABLE workflow_node_mapping
    ADD COLUMN source_node_key VARCHAR(64);

-- What was written as nothing but a placeholder was always a reference; it is
-- recorded as one, keeping the field it named. `{{input.reply}}` becomes a
-- reference to `reply`, `{{trigger.channel}}` to `trigger.channel` — the prefix
-- is kept there because it names a different source, not a different field.
--
-- Anything mixing text and placeholders stays a value for now: it still resolves
-- the way it always did, and the editor offers to split it when it is next
-- opened. Converting it here would silently drop the text around the
-- placeholder.
UPDATE workflow_node_mapping
SET mode = 'REFERENCE',
    expression = regexp_replace(
        btrim(expression),
        '^\{\{\s*(?:input\.)?([A-Za-z_][A-Za-z0-9_.]*)\s*\}\}$',
        '\1'
    )
WHERE btrim(expression) ~ '^\{\{\s*(?:input\.)?[A-Za-z_][A-Za-z0-9_.]*\s*\}\}$';

UPDATE workflow_node_mapping
SET mode = 'REFERENCE',
    expression = regexp_replace(
        btrim(expression),
        '^\{\{\s*(trigger\.[A-Za-z_][A-Za-z0-9_.]*)\s*\}\}$',
        '\1'
    )
WHERE btrim(expression) ~ '^\{\{\s*trigger\.[A-Za-z_][A-Za-z0-9_.]*\s*\}\}$';
