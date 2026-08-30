-- A provider that is not asked whether it is there.

-- The sweep in ModelProviderMonitor calls every configured provider every five
-- minutes so that "Connected" on the Models screen means something today rather
-- than the day somebody last pressed the button. That is right for a provider
-- an installation pays for. It is wrong for the other kind: the endpoint
-- somebody keeps configured against a box that is only sometimes running - a
-- laptop's llama.cpp, a model server started for an afternoon - where the box
-- being off is the normal state and every sweep produces a connection refused,
-- a stack trace in the log, and a red row on a screen for something nobody
-- thinks is broken.
--
-- True for every row, which is what an installation already has and what a new
-- provider should get: not being asked is the deliberate choice, so it is the
-- one somebody has to make. NOT NULL rather than nullable-means-yes, because
-- there is no third state here - the sweep either calls it or it does not, and
-- a null would have to be read as one of the two anyway.
--
-- This governs the timer only. Test Connection runs whatever the column says,
-- and so does every chat and task that uses the provider - what is turned off
-- is asking on somebody's behalf, not the provider itself.
--
-- No index. It is read off rows already fetched by the sweep's own findAll, and
-- a workspace has a handful of providers.

ALTER TABLE model_provider ADD COLUMN check_enabled BOOLEAN NOT NULL DEFAULT TRUE;
