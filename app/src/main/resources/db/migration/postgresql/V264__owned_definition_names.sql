-- A workflow's own definition is unique within that workflow, not the workspace.
--
-- A Custom action, condition or trigger belongs to one node, is reached only
-- through that node, and appears in no list of its own - so two workflows may
-- each have a "Format agent output" and neither is ambiguous. The constraint
-- asked the workspace instead, and node names repeat across workflows by
-- nature: the second workflow to want one simply could not save, under an
-- error naming a definition its author had never seen.
--
-- The shared lists keep their old rule, because those are lists people read:
-- one name once. That is what the partial index on `workflow_id is null` says.
alter table workflow_action drop constraint if exists uk_workflow_action_name;
alter table workflow_condition drop constraint if exists uk_workflow_condition_name;
alter table workflow_trigger drop constraint if exists uk_workflow_trigger_name;

create unique index if not exists uk_workflow_action_name_shared
    on workflow_action (workspace_id, name) where workflow_id is null;
create unique index if not exists uk_workflow_action_name_owned
    on workflow_action (workspace_id, workflow_id, name) where workflow_id is not null;

create unique index if not exists uk_workflow_condition_name_shared
    on workflow_condition (workspace_id, name) where workflow_id is null;
create unique index if not exists uk_workflow_condition_name_owned
    on workflow_condition (workspace_id, workflow_id, name) where workflow_id is not null;

create unique index if not exists uk_workflow_trigger_name_shared
    on workflow_trigger (workspace_id, name) where workflow_id is null;
create unique index if not exists uk_workflow_trigger_name_owned
    on workflow_trigger (workspace_id, workflow_id, name) where workflow_id is not null;
