-- What a node calls what it produces.
--
-- An agent answers with prose. Prose has no fields, so a later node had nothing
-- to refer to: `{{input.something}}` could never resolve against it, and the
-- only thing a downstream node could do with an answer was pass it along whole.
--
-- Naming the output is what makes it addressable. The step's output becomes an
-- object with this one key, so a send step can say `{{input.reply}}` and mean
-- the answer rather than the entire payload.
--
-- Null throughout, and null keeps the old behaviour: the output is handed on
-- unchanged. Nothing already drawn changes shape because this column appeared.

ALTER TABLE workflow_node
    ADD COLUMN output_name VARCHAR(60);

ALTER TABLE execution_step
    ADD COLUMN output_name VARCHAR(60);
