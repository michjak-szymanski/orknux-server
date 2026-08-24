-- A script may call another one.
--
-- Until now every function and every tool was a closed world: whatever it did, it
-- did in one file, and the only way to share a piece of it was to paste the piece
-- into the next file. So the same date parser, the same slug rule and the same
-- half-page of validation exist several times over in workspaces that have been
-- running for a while, and fixing one of them fixes one of them.
--
-- An import is stored as two things that are deliberately not the same fact. The
-- id is the reference, because a reference held by name is a reference stranded the
-- first time somebody renames what it points at - this product has already learnt
-- that once, with grants. The name is the importer's own word for it, written into
-- the importer's own code, which is why it is not the imported thing's name: if it
-- were, renaming a function would break every function that called it while the
-- reference itself survived, and the failure would arrive mid-run rather than at
-- the moment somebody was looking.
--
-- No foreign key on imported_id, in keeping with every other id-holding column here
-- - a parameter's object, a condition's function. The delete is refused by the API,
-- which can say which functions and tools are in the way; a constraint could only
-- say that something was. And a foreign key between two aggregates that a workspace
-- delete takes together is the shape #169 was.
CREATE TABLE workflow_function_import
(
    function_id BIGINT      NOT NULL REFERENCES workflow_function (id) ON DELETE CASCADE,
    position    INTEGER     NOT NULL,
    imported_id BIGINT      NOT NULL,
    import_name VARCHAR(64) NOT NULL,
    PRIMARY KEY (function_id, position),
    -- Two imports under one name are one the code can reach and one it cannot,
    -- and which is which is whichever the object literal was built with last.
    CONSTRAINT uq_workflow_function_import_name UNIQUE (function_id, import_name)
);

-- The same table for a tool, pointing at the same functions.
--
-- A tool and a function are the same JavaScript in the same sandbox; what differs
-- is who calls them. So a tool imports a function exactly as a function does, and
-- a workspace that has worked out how importing goes in one editor does not have
-- to work it out again in the other.
--
-- One direction only, and there is no table for the other. Nothing imports a tool,
-- because a tool is what an agent decides to call and not a piece anybody builds
-- out of - which is also why a tool cannot be in an import loop and a function can.
CREATE TABLE agent_tool_import
(
    tool_id     BIGINT      NOT NULL REFERENCES agent_tool (id) ON DELETE CASCADE,
    position    INTEGER     NOT NULL,
    imported_id BIGINT      NOT NULL,
    import_name VARCHAR(64) NOT NULL,
    PRIMARY KEY (tool_id, position),
    CONSTRAINT uq_agent_tool_import_name UNIQUE (tool_id, import_name)
);

-- What imports a given function, which is the question a delete asks.
CREATE INDEX ix_workflow_function_import_imported ON workflow_function_import (imported_id);
CREATE INDEX ix_agent_tool_import_imported ON agent_tool_import (imported_id);
