-- A fourth kind of model: one that listens.
--
-- Chat, embedding and completion were the kinds anything here called; speech is
-- the first that is not a language model answering a prompt, and it is chosen
-- and metered like the rest — a provider, an endpoint, a model name.

ALTER TABLE llm_model
    DROP CONSTRAINT ck_llm_model_kind;

ALTER TABLE llm_model
    ADD CONSTRAINT ck_llm_model_kind CHECK (
        kind IN ('CHAT', 'EMBEDDING', 'COMPLETION', 'TRANSCRIPTION')
    );

-- Which model a workspace speaks to.
--
-- Per workspace rather than per chat: it is a setting about this installation's
-- hardware — where Whisper is running — not a choice somebody makes per message.
-- Null means the microphone is not offered at all, which is the right default
-- for an installation with nothing to transcribe with.
ALTER TABLE workspace
    ADD COLUMN transcription_model_id BIGINT REFERENCES llm_model (id) ON DELETE SET NULL;
