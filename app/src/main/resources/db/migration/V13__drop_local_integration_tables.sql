-- Connections, MCP servers and every credential now live in gyloli-connector.
-- gyloli-server keeps only what it can decide on: who may see a team, and what
-- was done. Nothing is copied across by this migration — a deployment holding
-- real credentials has to export them into the connector before it runs.
DROP TABLE IF EXISTS team_connection_header;
DROP TABLE IF EXISTS mcp_server_header;
DROP TABLE IF EXISTS team_connection;
DROP TABLE IF EXISTS mcp_server;
DROP TABLE IF EXISTS connection;
