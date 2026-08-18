-- OBJECT names one of the workspace's objects; MAP is the shape nobody defined.
--
-- Until now OBJECT meant "something object-shaped", which is the least a type can
-- say. The workspace already keeps object definitions — a trigger says which one a
-- webhook must send — and a function's parameters had no way to point at one, so
-- the editor could only annotate them `Record<string, unknown>` and the language
-- service had nothing to check a field access against.
--
-- So the two meanings are separated. OBJECT carries an object id and means that
-- shape. MAP carries nothing and means what OBJECT used to: keys and values that
-- are only known when the code looks.
ALTER TABLE workflow_function_param
    ADD COLUMN object_id BIGINT;

ALTER TABLE workflow_function
    ADD COLUMN return_object_id BIGINT;

-- The type names are pinned by check constraints, so the new one has to be let in
-- before anything can be written as it. Dropped and rewritten rather than altered:
-- a check constraint has no ALTER, and naming the full list again is also the only
-- place a reader can see what the column actually accepts.
ALTER TABLE workflow_function_param
    DROP CONSTRAINT ck_workflow_function_param_type;

ALTER TABLE workflow_function_param
    ADD CONSTRAINT ck_workflow_function_param_type
        CHECK (type IN ('STRING', 'NUMBER', 'BOOLEAN', 'OBJECT', 'MAP', 'ARRAY'));

ALTER TABLE workflow_function
    DROP CONSTRAINT ck_workflow_function_return;

ALTER TABLE workflow_function
    ADD CONSTRAINT ck_workflow_function_return
        CHECK (return_type IN ('STRING', 'NUMBER', 'BOOLEAN', 'OBJECT', 'MAP', 'ARRAY', 'NONE'));

-- Everything stored as OBJECT was written under the old meaning, so it becomes MAP.
-- Nothing is lost and nothing starts lying: a parameter that was "some object" is
-- still "some object", now under the name that says so. Whoever wants the checked
-- version picks an object in the editor, which is a decision only they can make.
UPDATE workflow_function_param
SET type = 'MAP'
WHERE type = 'OBJECT';

UPDATE workflow_function
SET return_type = 'MAP'
WHERE return_type = 'OBJECT';

-- An object type names an object; anything else names none. Enforced here as well
-- as in the API, because this is the invariant the editor's annotations rely on:
-- a parameter that says OBJECT and points nowhere would be annotated with a type
-- that does not exist.
ALTER TABLE workflow_function_param
    ADD CONSTRAINT ck_workflow_function_param_object
        CHECK ((type = 'OBJECT' AND object_id IS NOT NULL) OR (type <> 'OBJECT' AND object_id IS NULL));

ALTER TABLE workflow_function
    ADD CONSTRAINT ck_workflow_function_return_object
        CHECK (
            (return_type = 'OBJECT' AND return_object_id IS NOT NULL)
                OR (return_type <> 'OBJECT' AND return_object_id IS NULL)
        );
