-- A workflow's own definitions may share a name with each other.
--
-- V264 scoped the name to its owner, which fixed two *workflows* each wanting
-- a "Format agent output" and left the other half of the same mistake: within
-- one workflow, two owned definitions still had to differ. They cannot.
--
-- Every action node arrives called "Action", and the Custom form names the
-- definition after the node it belongs to - so the second Custom action in a
-- workflow is named "Action" against an "Action" that is already there, and
-- the panel, which writes itself and has no button to press, simply never
-- saves. What somebody sees is a node that forgets its action on reload.
--
-- A name is a list's way of telling two things apart, and an owned definition
-- is in no list: it is reached through the node that uses it and dies with the
-- workflow. So the name is a label, and labels repeat. The shared list keeps
-- its uniqueness, because that one is read.
drop index if exists uk_workflow_action_name_owned;
drop index if exists uk_workflow_condition_name_owned;
drop index if exists uk_workflow_trigger_name_owned;
