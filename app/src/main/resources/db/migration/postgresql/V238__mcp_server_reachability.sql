-- A registered MCP server carries the result of its last check, so the list can
-- show reachability without anybody pressing Check. Null means not checked yet,
-- which the screen draws as neither reachable nor failed. Filled by the Check
-- button and by McpServerMonitor's timer. Issue #329.
ALTER TABLE mcp_server ADD COLUMN last_checked_at TIMESTAMPTZ;
ALTER TABLE mcp_server ADD COLUMN reachable BOOLEAN;
ALTER TABLE mcp_server ADD COLUMN check_detail VARCHAR(1000);
ALTER TABLE mcp_server ADD COLUMN tool_count INTEGER;
