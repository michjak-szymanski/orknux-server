-- A tool an agent called and a function running anywhere are two waits, and
-- they were one number.
--
-- One setting bounded a workflow step, a condition, a webhook's answer and a
-- tool an agent called. The last of those is a model stopped mid-turn and, in
-- a chat, a person watching it happen; the others have nobody waiting in
-- particular. Twenty seconds is patience in one and a failure in the other, so
-- the number suited neither.
--
-- The old column keeps the functions, which is what it is named after and what
-- three of the four callers were. The tools get their own, seeded from it: an
-- installation that set five seconds meant five seconds for the tools it was
-- looking at when it set them, and nothing about this split should change what
-- any workspace already does.
alter table workspace
    add column tool_timeout_seconds integer;

alter table workspace
    add constraint ck_workspace_tool_timeout
        check (tool_timeout_seconds is null or (tool_timeout_seconds between 1 and 600));

update workspace
set tool_timeout_seconds = script_timeout_seconds
where script_timeout_seconds is not null;
