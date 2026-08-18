-- Whether an agent may ask orknux about orknux.
--
-- The built-in server is not one of the workspace's MCP servers and never
-- appears among them: those are things somebody registered, with an address and
-- a credential, and this one is the application the agent is already inside. So
-- the grant is a column here rather than a row in `agent_mcp_server`, which
-- would have needed a server nobody added to point at.
--
-- Off for every agent that already exists, which is the only safe direction: a
-- grant that appeared by upgrading is a grant nobody decided to give.
ALTER TABLE agent
    ADD COLUMN orknux_access BOOLEAN NOT NULL DEFAULT FALSE;
