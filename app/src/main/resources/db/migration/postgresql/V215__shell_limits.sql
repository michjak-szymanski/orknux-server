-- What one machine is allowed, as against what the installation allows.
--
-- Two numbers governed how a shell command ran and both lived only in
-- configuration: how long a command may run, and how much of its output is
-- kept. Sixty seconds and 64 KiB, chosen against the commands somebody types by
-- hand and right for those - and wrong for the thing this product ships a box
-- for. docker/coder arrives with an empty Maven repository, so the first build
-- an agent runs on it downloads a framework before it compiles a line. The
-- agent was handed a timeout it did not cause and could not fix, and a failing
-- build's output was cut before the compile error, which left a model unable to
-- tell "the build failed" from "the output stopped".
--
-- The shipped defaults move with this change - ten minutes and 256 KiB, in
-- ShellProperties, where the reasoning is written out. These columns are the
-- other half: a build machine and a router want opposite numbers, and an
-- installation holding both should not have to pick one.
--
-- Null rather than the default copied in when a row is written. A copy is a
-- promise made on the day the shell was added: raise the installation's number
-- afterwards and every machine that never asked for anything different would
-- stay behind on the old one, with nothing in the row to tell those apart from
-- the machines somebody chose deliberately. Null means "whatever the
-- installation says", and it keeps meaning that.
--
-- No index. Neither column is ever selected on - they are read off a row already
-- fetched by id, on the way into one SSH command.

ALTER TABLE shell ADD COLUMN command_timeout_seconds INTEGER;
ALTER TABLE shell ADD COLUMN max_output_bytes INTEGER;
