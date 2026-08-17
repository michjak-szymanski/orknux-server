-- What a variable is for, in the words of whoever put it there.
--
-- A name has to be an identifier, because a function receives it as an argument,
-- and identifiers are poor at saying things: `slack_signing_secret_v2` cannot
-- explain which app it belongs to or why there are two. The list shows this
-- beside the name, which is the question somebody has when they find one they
-- did not create.

ALTER TABLE workspace_variable
    ADD COLUMN description VARCHAR(500);

-- Who put it there, beside who touched it last.
--
-- A secret nobody recognises is a question about its author before it is a
-- question about its value: the person who added it knows what it is for and
-- whether it can go. Existing rows have nobody to name, and say so.

ALTER TABLE workspace_variable
    ADD COLUMN created_by VARCHAR(120) NOT NULL DEFAULT '';
