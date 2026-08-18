-- What a workflow was when it was published.
--
-- Until now "published" was a word on a screen. Nothing read it: a trigger
-- fired every workflow that had a node instancing it, and the runner read the
-- rows as they were at that moment - so an event arriving while somebody was
-- halfway through drawing ran the half-drawn graph. The badge said Draft and
-- the graph ran anyway.
--
-- So publishing now takes a copy, and that copy is what runs. The editable
-- rows go on being the draft: saving them is safe, which is what makes it
-- possible to leave a graph half-finished overnight.
--
-- One row per workflow, holding the graph as the execution module reads it -
-- nodes, their bindings, the edges and their branches - rather than a second
-- set of tables shadowing the first. A snapshot is not a thing anybody edits
-- or queries a field of; it is read whole, by one caller, and rebuilding the
-- schema for it in a second shape would be a second schema to migrate.
CREATE TABLE workflow_publication (
    workflow_id BIGINT PRIMARY KEY REFERENCES workflow (id) ON DELETE CASCADE,
    published_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_by VARCHAR(120) NOT NULL DEFAULT 'system',
    graph JSONB NOT NULL
);
