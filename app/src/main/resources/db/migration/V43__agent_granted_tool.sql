-- Which of the workspace's tools an agent may call.
--
-- A grant, like the memory and skill catalogs and the MCP servers, and by name
-- for the same reason: an agent is configured against what the workspace calls
-- things. An agent granted none calls none — every workspace tool being
-- available to every agent by default would make the grant a decoration, and a
-- tool is code that does something rather than a page an agent reads.
--
-- Named `agent_granted_tool` because `agent_tool` is the tool catalogue itself.
CREATE TABLE agent_granted_tool (
    agent_id BIGINT       NOT NULL REFERENCES agent (id) ON DELETE CASCADE,
    position INTEGER      NOT NULL,
    name     VARCHAR(120) NOT NULL,
    PRIMARY KEY (agent_id, position)
);
