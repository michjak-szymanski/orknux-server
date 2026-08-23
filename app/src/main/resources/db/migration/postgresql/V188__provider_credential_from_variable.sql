-- A model provider may read its credential from a workspace variable secret.
--
-- Until now a provider held its own copy of the key, encrypted in
-- model_provider.secret. That is still what most of them will do and nothing
-- about it changes. What it could not do is share: an installation that puts one
-- OpenAI key behind three providers, or that rotates keys in one place, had to
-- retype the key into each provider and remember which ones it had missed. The
-- workspace already has somewhere to keep a secret once and read it from
-- several places, and this points a provider at it.
--
-- One column, and it is the choice as well as the reference. A provider with
-- secret_variable_id set reads that variable; one without it keeps its own copy
-- in secret. There is no third column saying which, because a discriminator
-- beside a nullable id can contradict it, and a state the database allows is a
-- state something will eventually be in.

-- By id, and that is the decision this migration is really making.
--
-- The other way was by name, which is how an agent's grant to an MCP server
-- works. That mechanism is what produced #170 and #228: a name is not an
-- identity, so renaming the thing or removing and re-registering it under the
-- same name silently rebinds or strands everything holding the name, and the
-- holder goes on displaying a grant it no longer has. A provider losing its key
-- because somebody tidied a variable catalog is exactly that failure with a
-- credential in it.
--
-- An id survives a rename and survives a move between catalogs, so those two
-- cost nothing and need no machinery to follow them. That leaves deletion, which
-- VariableAPI refuses while a provider reads the variable, naming the providers
-- that hold it - the same shape the guard on a function's external parameter has
-- had since #165.
ALTER TABLE model_provider
    ADD COLUMN secret_variable_id BIGINT;

-- No foreign key, and its absence is deliberate.
--
-- model_provider belongs to the connection module and workspace_variable to the
-- application; module tables carry no keys across that boundary, which is why
-- model_provider has never had one to workspace even for workspace_id. So the
-- referential guarantee is in code rather than in the schema, and the code is
-- written to expect that it can still come apart - a restore of one table
-- without the other, a workspace removed out from under a provider, a database
-- edited by hand. A dangling reference is reported as one, on the provider's
-- card and in the words of its connection check, rather than read as a provider
-- that was never configured.
--
-- What is enforced here is the part that is local to this table: the two kinds
-- of credential are exclusive. A provider told to read a variable while still
-- holding an old copy of a key would be a credential kept past the moment
-- somebody decided to stop keeping it, and it would be unclear which one a call
-- used.
ALTER TABLE model_provider
    ADD CONSTRAINT ck_model_provider_credential
        CHECK (secret_variable_id IS NULL OR secret IS NULL);
