-- Runs now live in gyloli-workflow, which carries them out. gyloli-server keeps
-- the workflow definitions and decides who may look at a team's runs. Nothing is
-- copied across by this migration: a deployment with runs worth keeping has to
-- export them into gyloli-workflow first.
DROP TABLE IF EXISTS execution_log;
DROP TABLE IF EXISTS execution_edge;
DROP TABLE IF EXISTS execution_step;
DROP TABLE IF EXISTS workflow_execution;
