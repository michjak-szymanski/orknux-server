-- A function that returns nothing.
--
-- Plenty of them do: post a message, write a row, call a webhook. Making them
-- declare OBJECT and hand back an empty one is a fiction the graph then has to
-- carry — a node with an output port nothing ever reads.
ALTER TABLE workflow_function DROP CONSTRAINT ck_workflow_function_return;
ALTER TABLE workflow_function ADD CONSTRAINT ck_workflow_function_return
    CHECK (return_type IN ('STRING', 'NUMBER', 'BOOLEAN', 'OBJECT', 'ARRAY', 'NONE'));
