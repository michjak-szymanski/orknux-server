-- Room for an encrypted credential.
--
-- Credentials are no longer stored as the text they are: each is sealed with
-- AES-GCM and kept as `orkx1:<iv>:<ciphertext>`, both parts base64. That is
-- about a third larger than the bytes going in, plus an initialisation vector,
-- an authentication tag and the prefix — so a value that just fitted in
-- VARCHAR(1000) lands near 1400 and would be rejected on write.
--
-- Widening only. Nothing here re-encrypts: that needs the key, which the
-- database does not have, so the rewriting is done by the application on
-- startup. Until a row has been rewritten it is still readable, because
-- decryption passes anything without the prefix through untouched.

ALTER TABLE model_provider
    ALTER COLUMN secret TYPE VARCHAR(4000);

ALTER TABLE workspace_connection
    ALTER COLUMN secret TYPE VARCHAR(4000);

ALTER TABLE workspace_connection
    ALTER COLUMN app_token TYPE VARCHAR(4000);

ALTER TABLE mcp_server
    ALTER COLUMN secret TYPE VARCHAR(4000);
