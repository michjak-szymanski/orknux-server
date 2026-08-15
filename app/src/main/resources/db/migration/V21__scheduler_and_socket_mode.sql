-- db-scheduler's own table. It is what makes a scheduled trigger fire once
-- across however many instances are running, and survive a restart.
CREATE TABLE scheduled_tasks
(
    task_name            TEXT                     NOT NULL,
    task_instance        TEXT                     NOT NULL,
    task_data            BYTEA,
    execution_time       TIMESTAMP WITH TIME ZONE NOT NULL,
    picked               BOOLEAN                  NOT NULL,
    picked_by            TEXT,
    last_success         TIMESTAMP WITH TIME ZONE,
    last_failure         TIMESTAMP WITH TIME ZONE,
    consecutive_failures INT,
    last_heartbeat       TIMESTAMP WITH TIME ZONE,
    version              BIGINT                   NOT NULL,
    priority             SMALLINT,
    PRIMARY KEY (task_name, task_instance)
);

CREATE INDEX execution_time_idx ON scheduled_tasks (execution_time);
CREATE INDEX last_heartbeat_idx ON scheduled_tasks (last_heartbeat);
CREATE INDEX priority_execution_time_idx ON scheduled_tasks (priority DESC, execution_time ASC);

-- When a scheduled trigger last fired, so a tick knows what it owes and a
-- restart does not fire everything again.
ALTER TABLE workflow_trigger
    ADD COLUMN last_fired_at TIMESTAMPTZ;

-- Slack over Socket Mode is its own connection type: it holds two credentials
-- and is the one gyloli opens a websocket for. The column is widened first,
-- since the name does not fit in what was there.
ALTER TABLE connection
    ALTER COLUMN type TYPE VARCHAR(24);
ALTER TABLE team_connection
    ALTER COLUMN type TYPE VARCHAR(24);

ALTER TABLE connection
    DROP CONSTRAINT ck_connection_type;
ALTER TABLE connection
    ADD CONSTRAINT ck_connection_type
        CHECK (type IN ('SLACK', 'SLACK_SOCKET_MODE', 'GITHUB', 'JIRA', 'TEAMS', 'WEBHOOK'));

ALTER TABLE team_connection
    DROP CONSTRAINT ck_team_connection_type;
ALTER TABLE team_connection
    ADD CONSTRAINT ck_team_connection_type
        CHECK (type IN ('SLACK', 'SLACK_SOCKET_MODE', 'GITHUB', 'JIRA', 'TEAMS', 'WEBHOOK'));
