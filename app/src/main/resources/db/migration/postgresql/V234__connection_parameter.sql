-- A parameter may be one of the workspace's connections.
--
-- What crosses is still the connection's id — this is a name on a value that
-- was already travelling, so that the panel can offer the workspace's
-- connections rather than a free box and the editor can annotate the parameter
-- as something to hand to a call that takes one. A function declaring `string`
-- worked and told nobody reading it what the string was for.
--
-- Both parameter tables, because a tool's parameters are the same list on the
-- same screen: widening one and not the other would make the type available in
-- a function and refused in a tool, for no reason anybody could see.
--
-- Not the return type. A function that answers "which connection" answers with
-- a string, and offering a return type nothing would sensibly produce is a
-- choice somebody has to think about once and never wants.
ALTER TABLE workflow_function_param DROP CONSTRAINT ck_workflow_function_param_type;
ALTER TABLE workflow_function_param ADD CONSTRAINT ck_workflow_function_param_type
    CHECK (type IN ('STRING', 'NUMBER', 'BOOLEAN', 'OBJECT', 'MAP', 'ARRAY', 'CONNECTION'));

ALTER TABLE agent_tool_param DROP CONSTRAINT ck_agent_tool_param_type;
ALTER TABLE agent_tool_param ADD CONSTRAINT ck_agent_tool_param_type
    CHECK (type IN ('STRING', 'NUMBER', 'BOOLEAN', 'OBJECT', 'MAP', 'ARRAY', 'CONNECTION'));
