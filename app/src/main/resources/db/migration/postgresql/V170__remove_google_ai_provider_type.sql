-- The GOOGLE_AI provider type goes.
--
-- It was never implemented, and unlike TEAMS it was not merely inert: it was
-- wrong. The one and only thing the code ever did with the type was pick an
-- auth header - `x-goog-api-key` - and that is the header Google's *native*
-- API wants. Every URL the code builds for a provider that is not Anthropic or
-- Azure is an OpenAI-shaped one: `{endpoint}/models` to check it and
-- `{endpoint}/chat/completions` to talk to it, carrying an OpenAI request body.
-- Google's native API has neither path; it has `/v1beta/models/{model}:generateContent`
-- and a body of its own. Google does publish an OpenAI-compatible surface at
-- `/v1beta/openai`, which those two paths and that body fit exactly - but it
-- authenticates with `Authorization: Bearer`, which is the one header GOOGLE_AI
-- made sure not to send.
--
-- So the type could not be pointed anywhere that worked. Pointed at the native
-- base it 404s on both paths. Pointed at `/v1beta` the *check* succeeds - the
-- model list is real, the header is right for it, and the listing parser
-- already reads Google's `models[].name` shape - and every chat 404s. That is
-- the worst of the failures available: the screen says Connected and nothing
-- can be said to the model.
--
-- CUSTOM, meanwhile, already works for Gemini today with no code at all: point
-- it at `https://generativelanguage.googleapis.com/v1beta/openai`, and the
-- Bearer header, the model list and the chat body are all the ones Google
-- documents for that surface. GOOGLE_AI was therefore strictly worse than
-- choosing CUSTOM for the same provider, which is what makes this a removal
-- rather than a fix: the working configuration is the one that has no type.
--
-- The rows are converted rather than deleted or refused, for the reasons V160
-- gave for TEAMS.
--
-- Converted to CUSTOM, because CUSTOM is by its own definition "anything that
-- speaks one of the above well enough, until it earns a type", and it is the
-- type that describes what a GOOGLE_AI row was already being treated as
-- everywhere except that header. A converted row keeps its endpoint and its
-- key and starts sending `Authorization: Bearer` - which is a change in
-- behaviour, and the right one: it is the difference between a row that cannot
-- work at any endpoint and a row that works at the OpenAI-compatible one.
--
-- Not deleted, because the row holds an endpoint and a credential somebody
-- entered on purpose, and because llm_model.provider_id references it ON DELETE
-- CASCADE: dropping the provider would silently take every model configured
-- against it, and every model is what a workflow actually names.
--
-- Not refused, because a migration that aborts startup over a type that never
-- worked is a worse failure than the dead value it was meant to remove.

UPDATE model_provider
SET type = 'CUSTOM'
WHERE type = 'GOOGLE_AI';

-- Narrowed only after the rows have moved; a CHECK is validated against what is
-- already in the table.
ALTER TABLE model_provider
    DROP CONSTRAINT ck_model_provider_type;
ALTER TABLE model_provider
    ADD CONSTRAINT ck_model_provider_type
        CHECK (type IN ('OPENAI', 'ANTHROPIC', 'AZURE_OPENAI', 'OLLAMA', 'CUSTOM'));
