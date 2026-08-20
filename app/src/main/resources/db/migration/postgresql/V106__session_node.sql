-- A node that names an LLM session.
--
-- The two parameters a session is identified by used to sit on the agent node
-- itself, which collapsed two ideas into one: an agent node said both "ask this
-- agent" and "and this is what the conversation is called". Two agents sharing
-- one conversation meant typing the same key into both, and nothing on the
-- canvas said they were sharing anything.
--
-- So the session becomes a node of its own, carrying `sessionKey` and the
-- optional `sessionKeyPrefix` as its mappings, and an edge from it to an agent
-- is what says that agent talks into it. Two agents sharing a session is two
-- edges from one node, which is a thing you can see.
--
-- The node kinds were listed when there were five of them, so a session node
-- would have been accepted by the application and refused here.
ALTER TABLE workflow_node
    DROP CONSTRAINT ck_workflow_node_kind;

ALTER TABLE workflow_node
    ADD CONSTRAINT ck_workflow_node_kind CHECK (
        kind IN ('TRIGGER', 'AGENT', 'ACTION', 'CONDITION', 'OBJECT', 'SESSION')
    );

-- `execution_step` is deliberately left alone.
--
-- A session node is a declaration, not a step: nothing runs it, and it produces
-- nothing a later node could read. The graph handed to the execution engine has
-- the session nodes folded into the agents they feed and then dropped, so no
-- run ever records a step of this kind. Widening that constraint too would say
-- the opposite - that a SESSION step is something we expect to see - and the
-- day one appeared it would be a bug the database no longer catches.
