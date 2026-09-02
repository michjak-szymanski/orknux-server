-- A certificate authority to trust for one MCP server, as PEM.
--
-- Nullable, and null is what every existing row means: trust what the JVM
-- trusts, which is what they have all been doing. Issue #322.
--
-- Not encrypted. A CA certificate is what a server hands every client that
-- connects; the key that signs with it is the secret, and that never arrives
-- here.
ALTER TABLE mcp_server ADD COLUMN ca_certificate TEXT;
