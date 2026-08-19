-- Whether a connection works is checked, not assumed: the outcome of the last
-- probe is kept so the team screen can report it.
ALTER TABLE team_connection
    ADD COLUMN last_check_status  VARCHAR(16),
    ADD COLUMN last_check_message VARCHAR(500),
    ADD COLUMN last_checked_at    TIMESTAMPTZ;

ALTER TABLE team_connection
    ADD CONSTRAINT ck_team_connection_check
        CHECK (last_check_status IS NULL OR last_check_status IN ('CONNECTED', 'FAILED'));
