-- A function condition without a function is a condition nobody can answer, and
-- the shape constraint says so — but the foreign key was nulling it when the
-- function went away, leaving a row that could not be written. Deleting a
-- function that something still uses is refused instead, the way it already is
-- for an action that calls one.
ALTER TABLE workflow_condition
    DROP CONSTRAINT workflow_condition_function_id_fkey;

ALTER TABLE workflow_condition
    ADD CONSTRAINT workflow_condition_function_id_fkey
        FOREIGN KEY (function_id) REFERENCES workflow_function (id);
