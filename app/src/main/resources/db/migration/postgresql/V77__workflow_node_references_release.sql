-- What happens to a node when the thing it points at is deleted.
--
-- Every other reference a node holds already answers this: an action, a
-- condition and a trigger are all ON DELETE SET NULL, so deleting a definition
-- leaves the node in the graph, pointing at nothing, and the run says so —
-- "The action this node ran has been deleted" — rather than the delete being
-- refused.
--
-- The agent reference was left without a delete action, which means the
-- database refuses. That is not a stricter policy, it is an omission, and it
-- has a consequence nobody asked for: deleting a *workspace* cascades to its
-- agents, so a workspace holding one workflow with an LLM Agent node in it
-- could not be deleted at all. The API answered INTERNAL_ERROR and the
-- administration page says deleting a workspace takes its contents with it.
--
-- The node's own runtime already expects the state this creates: an agent node
-- with no agent reports that it names none, exactly as it does for a node that
-- never had one.
alter table workflow_node
    drop constraint workflow_node_agent_id_fkey;

alter table workflow_node
    add constraint workflow_node_agent_id_fkey
        foreign key (agent_id) references agent (id) on delete set null;

-- And the object reference had no constraint at all, so nothing stopped a node
-- from naming an object that no longer exists. Added with the same answer as
-- its siblings, which is also what the graph validator already assumes.
alter table workflow_node
    add constraint workflow_node_object_id_fkey
        foreign key (object_id) references workflow_object (id) on delete set null;
