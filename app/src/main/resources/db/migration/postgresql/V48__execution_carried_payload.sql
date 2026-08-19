-- What a run is carrying, kept with the run.
--
-- A step used to be handed the payload and to hand back what it produced. On
-- the Temporal engine that means both cross an activity boundary, and an
-- activity's arguments and results are written into event history and kept for
-- the life of the run — so a payload that grows as it passes down the graph is
-- recorded again, larger, at every single step. Fine for a Slack message;
-- ruinous for anything that moves real data, and bounded by a payload limit
-- that has nothing to do with what a workflow ought to be able to carry.
--
-- So the payload lives here and Temporal carries an execution id. Steps read it
-- and write it back, which also means a step resumed on another worker reads
-- exactly what the one before it left.
--
-- Null until a step completes: until then the run is still carrying its input.

ALTER TABLE workflow_execution
    ADD COLUMN carried TEXT;
