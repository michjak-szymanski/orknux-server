-- The MCP session a shell session is, when the far side jails each one. Set only
-- for an MCP shell whose server advertised session isolation: then the session's
-- own root is the sandbox, there is no directory of ours, and this id is what a
-- run threads through and a close ends. Null for SSH shells and for MCP servers
-- that do not jail, both of which carry a directory instead. Issue #337.
ALTER TABLE shell_session ADD COLUMN mcp_session VARCHAR(200);
