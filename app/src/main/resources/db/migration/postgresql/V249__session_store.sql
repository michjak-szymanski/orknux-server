-- An AI session's own store: what one tool call puts, a later one gets, for
-- as long as the session lives - and no other session ever sees it. This is
-- what lets a plugin keep its place between the calls of one conversation
-- without anything surviving into another. The rows go with the session,
-- which is the whole of the lifetime rule.
CREATE TABLE llm_session_store (
    session_id BIGINT       NOT NULL REFERENCES llm_session (id) ON DELETE CASCADE,
    name       VARCHAR(200) NOT NULL,
    value      TEXT         NOT NULL,
    PRIMARY KEY (session_id, name)
);
