-- A description is what the model reads to decide whether to call the thing,
-- and a good one quotes an example call. 500 characters refused the example --
-- and refused it as INTERNAL_ERROR, because the only guard was the column.
--
-- Both tables, because a function's description feeds the same prompts through
-- a tool's imports, and the same paste would hit the same wall there.
ALTER TABLE agent_tool ALTER COLUMN description TYPE varchar(4000);
ALTER TABLE workflow_function ALTER COLUMN description TYPE varchar(4000);
