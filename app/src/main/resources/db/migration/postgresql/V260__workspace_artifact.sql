-- A file an agent saved, because it made something worth keeping.
--
-- The third kind of artifact and the first that is not a picture drawn from a
-- description: no prompt and no image model, just a name the agent chose and
-- bytes it handed over. A table of its own for that reason - folding it into
-- either picture table would mean a prompt column holding something that is
-- not one.
--
-- The bytes live in the installation's own attachment store, as both picture
-- tables' do. That is the rule the Artifacts page keeps: it lists what this
-- server hosts, so Download has something to hand over and Delete has
-- something to remove.
CREATE TABLE workspace_artifact
(
    id           bigserial PRIMARY KEY,
    workspace_id bigint       NOT NULL REFERENCES workspace (id) ON DELETE CASCADE,
    name         varchar(255) NOT NULL,
    description  text         NOT NULL,
    content_type varchar(120) NOT NULL,
    size_bytes   bigint       NOT NULL,
    location     varchar(1000) NOT NULL,
    saved_by     varchar(255) NOT NULL,
    saved_at     timestamptz  NOT NULL DEFAULT now()
);

-- The Artifacts page reads a workspace's own, and nothing else asks.
CREATE INDEX idx_workspace_artifact_workspace ON workspace_artifact (workspace_id, saved_at DESC);
