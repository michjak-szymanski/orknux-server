-- The CUSTOM provider type goes, and its rows become OPENAI.
--
-- Every other value in this enum answers one question: what does the endpoint
-- speak. OPENAI is the shape, ANTHROPIC has its own body and its own
-- `/messages` path, AZURE_OPENAI puts the deployment and the version in the
-- URL, OLLAMA is the OpenAI shape hung off `/v1`. CUSTOM answered a different
-- question - who is at the other end - and answered it with "nobody we have
-- heard of". Read as an answer to the first question, which is the only
-- question this column is asked, it promises that whatever the server speaks
-- will be handled.
--
-- Nothing behind that promise was ever built. CUSTOM branched on nothing at
-- all: the single place in the codebase that named it grouped it with OPENAI,
-- and every other decision made about a provider - the auth header, the URL,
-- the request body, the streaming events, the model listing - let it fall
-- through to the OpenAI default. A CUSTOM provider was an OpenAI provider,
-- called with `Authorization: Bearer`, at `{endpoint}/models` and
-- `{endpoint}/chat/completions`, carrying an OpenAI body. So the type offered
-- any format and delivered exactly one, which is the failure this removes.
--
-- OPENAI is where the rows go, because that is what they already were. The
-- conversion changes the name of the type and nothing else: the endpoint, the
-- credential, the auth method and the models configured against the provider
-- are all untouched, and the bytes that go out on the next call are the same
-- bytes that went out on the last one. There is no behaviour to warn about
-- here, which is what distinguishes this from V170 - GOOGLE_AI's rows started
-- sending a different header when they moved, and these do not.
--
-- The part of such a provider that is genuinely its own is the address it
-- lives at, and the endpoint column carries that through unread by this
-- migration. Anything OpenAI-shaped at an address of its own - a local
-- llama.cpp, an inference gateway, Google's OpenAI-compatible surface at
-- `/v1beta/openai` - is now called what it speaks rather than what we do not
-- know about it.
--
-- Converted rather than deleted, for V170's reason: llm_model references the
-- provider ON DELETE CASCADE, so dropping a provider takes with it every model
-- a workflow names.

UPDATE model_provider
SET type = 'OPENAI'
WHERE type = 'CUSTOM';

-- Narrowed only after the rows have moved; a CHECK is validated against what is
-- already in the table.
ALTER TABLE model_provider
    DROP CONSTRAINT ck_model_provider_type;
ALTER TABLE model_provider
    ADD CONSTRAINT ck_model_provider_type
        CHECK (type IN ('OPENAI', 'ANTHROPIC', 'AZURE_OPENAI', 'OLLAMA'));
