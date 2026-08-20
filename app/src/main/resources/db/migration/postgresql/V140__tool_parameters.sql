-- A tool says what it takes, instead of everybody guessing.
--
-- Until now a tool had no signature at all. Not one derived from its code and not
-- one stored beside it: the schema every model was shown declared exactly one
-- optional argument called `input`, hard-coded, and the sandbox was handed a
-- one-element list to match. Whatever a tool actually wanted lived in the English
-- of its description, and an agent had to read that and guess the rest.
--
-- So a tool's parameters become what a function's already are — a stored, ordered,
-- typed list the editor writes and the code is annotated from. The table is the
-- one workflow_function_param has, down to the column names and the checks, because
-- they are the same thing: arguments to a script in the same sandbox.
CREATE TABLE agent_tool_param
(
    tool_id   BIGINT      NOT NULL REFERENCES agent_tool (id) ON DELETE CASCADE,
    position  INTEGER     NOT NULL,
    name      VARCHAR(64) NOT NULL,
    type      VARCHAR(16) NOT NULL,
    object_id BIGINT,
    PRIMARY KEY (tool_id, position),
    CONSTRAINT ck_agent_tool_param_type
        CHECK (type IN ('STRING', 'NUMBER', 'BOOLEAN', 'OBJECT', 'MAP', 'ARRAY')),
    -- An object type names an object; anything else names none. The same invariant
    -- a function's parameters are held to, and for the same reason: OBJECT pointing
    -- nowhere would be annotated in the editor with a type that does not exist.
    CONSTRAINT ck_agent_tool_param_object
        CHECK ((type = 'OBJECT' AND object_id IS NOT NULL) OR (type <> 'OBJECT' AND object_id IS NULL))
);

-- Every tool that already exists is given the one parameter it always had.
--
-- This is not a default anybody is choosing for them: `input` is the name the
-- schema declared and the name the caller unwrapped, so writing it down changes
-- nothing about how these tools are offered or called. It only makes the thing
-- that was hard-coded into something somebody can now edit.
INSERT INTO agent_tool_param (tool_id, position, name, type)
SELECT id, 0, 'input', 'MAP'
FROM agent_tool;
