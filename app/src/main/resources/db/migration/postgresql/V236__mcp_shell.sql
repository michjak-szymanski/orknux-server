-- A shell whose transport is an MCP server's tools rather than an SSH session.
--
-- Reaching a machine used to mean opening sshd to this server and handing it a
-- private key. An MCP shell reaches one through a tool server installed there
-- instead - registered, credentialed and proxied exactly as MCP servers already
-- are - so the row points at one of those rather than carrying an address of
-- its own. Issue #337.
--
-- Existing rows are SSH by construction, which is what the default writes.
ALTER TABLE shell
    ADD COLUMN kind VARCHAR(10) NOT NULL DEFAULT 'SSH';
ALTER TABLE shell
    ADD COLUMN mcp_server_id BIGINT;
