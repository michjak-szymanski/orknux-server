-- One kind of agent.
--
-- REACT and LLM never differed in how they ran: both reach the model the same
-- way and both may call tools. What an agent is allowed to call is a per-agent
-- setting, which is what the choice was standing in for. Keeping a type nobody
-- could tell apart meant asking a question at creation that had no consequence.
--
-- Existing agents are moved rather than refused: an agent recorded as REACT is
-- the same agent, described by a name that has been withdrawn.

UPDATE agent
SET type = 'LLM'
WHERE type = 'REACT';

ALTER TABLE agent
    DROP CONSTRAINT IF EXISTS ck_agent_type;

ALTER TABLE agent
    ADD CONSTRAINT ck_agent_type CHECK (type IN ('LLM'));
