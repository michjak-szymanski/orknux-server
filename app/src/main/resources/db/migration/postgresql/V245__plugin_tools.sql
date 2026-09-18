-- A plugin offers tools to agents apart from the functions it offers to
-- workflows. The column holds the declaration as JSON, like declared_functions
-- beside it; a tool proxying one of the functions names it under proxyOf
-- inside the JSON rather than in a column, because nothing queries by it.
ALTER TABLE plugin ADD COLUMN declared_tools TEXT NOT NULL DEFAULT '[]';
