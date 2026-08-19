-- A fifth kind of model: one that speaks.
--
-- The mirror of V59's transcription. That kind listens — speech in, text out —
-- and this one is the same exchange the other way round, for reading an answer
-- aloud rather than for typing one by voice.
ALTER TABLE llm_model
    DROP CONSTRAINT ck_llm_model_kind;

ALTER TABLE llm_model
    ADD CONSTRAINT ck_llm_model_kind CHECK (
        kind IN ('CHAT', 'EMBEDDING', 'COMPLETION', 'TRANSCRIPTION', 'SPEECH')
    );

-- Which voice that model reads in.
--
-- On the model rather than on the workspace because it is the model's own
-- vocabulary: OpenAI knows `alloy` and `nova`, a local Kokoro server knows
-- neither, and a name from one is a 400 from the other. Null sends no voice at
-- all, which is what a server with a single built-in voice wants.
ALTER TABLE llm_model
    ADD COLUMN voice VARCHAR(80);

-- Which model a workspace reads with.
--
-- Per workspace and not per chat, for the reason the transcription model is:
-- it is a fact about what this installation is running, not a choice somebody
-- makes per message. Null means no speaker is offered on an answer at all,
-- which is the right default for an installation with nothing to speak with.
ALTER TABLE workspace
    ADD COLUMN speech_model_id BIGINT REFERENCES llm_model (id) ON DELETE SET NULL;
