-- A provider is not just an endpoint and a key: Azure OpenAI needs an API
-- version, a deployment and a region, and it can authenticate either with a key
-- or through Entra ID, which is a tenant, a client and a scope instead.
--
-- The status columns are the same three team_connection carries, and for the
-- same reason: what the screen reports should be what the provider answered,
-- not whether somebody typed a credential in.

ALTER TABLE model_provider
    ADD COLUMN type VARCHAR(24) NOT NULL DEFAULT 'OPENAI';

ALTER TABLE model_provider
    ADD COLUMN auth_method VARCHAR(16) NOT NULL DEFAULT 'API_KEY';

-- Azure OpenAI's own settings; null on every other type.
ALTER TABLE model_provider
    ADD COLUMN api_version VARCHAR(32);

ALTER TABLE model_provider
    ADD COLUMN deployment_name VARCHAR(120);

ALTER TABLE model_provider
    ADD COLUMN region VARCHAR(64);

-- Entra ID. The client secret is kept in `secret`, the same column an API key
-- uses, so there is still one place credentials live.
ALTER TABLE model_provider
    ADD COLUMN tenant_id VARCHAR(120);

ALTER TABLE model_provider
    ADD COLUMN client_id VARCHAR(120);

ALTER TABLE model_provider
    ADD COLUMN scope VARCHAR(300);

ALTER TABLE model_provider
    ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'NOT_CONFIGURED';

ALTER TABLE model_provider
    ADD COLUMN last_check_message VARCHAR(500);

ALTER TABLE model_provider
    ADD COLUMN last_checked_at TIMESTAMP WITH TIME ZONE;

-- Rows written before this migration were configured or not by their key alone.
UPDATE model_provider
SET status = 'NOT_CHECKED'
WHERE secret IS NOT NULL
  AND secret <> '';

ALTER TABLE model_provider
    ADD CONSTRAINT ck_model_provider_type
        CHECK (type IN ('OPENAI', 'ANTHROPIC', 'AZURE_OPENAI', 'GOOGLE_AI', 'OLLAMA', 'CUSTOM'));

ALTER TABLE model_provider
    ADD CONSTRAINT ck_model_provider_auth
        CHECK (auth_method IN ('API_KEY', 'ENTRA_ID'));

ALTER TABLE model_provider
    ADD CONSTRAINT ck_model_provider_status
        CHECK (status IN ('NOT_CONFIGURED', 'NOT_CHECKED', 'CONNECTED', 'FAILED'));
