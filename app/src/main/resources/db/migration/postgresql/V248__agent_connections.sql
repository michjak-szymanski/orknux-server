-- Which of the workspace's connections an agent may name when a tool takes
-- one. A grant like agent_granted_tool, but by id rather than by name: a
-- connection is referenced by id everywhere else (a plugin's setting, a
-- node's choice), and a grant that stopped meaning anything when the
-- connection was renamed would be a trap. The briefing lists these with a
-- standing instruction to use one only when explicitly told to.
CREATE TABLE agent_connection (
    agent_id      BIGINT  NOT NULL REFERENCES agent (id) ON DELETE CASCADE,
    position      INTEGER NOT NULL,
    connection_id BIGINT  NOT NULL,
    PRIMARY KEY (agent_id, position)
);
