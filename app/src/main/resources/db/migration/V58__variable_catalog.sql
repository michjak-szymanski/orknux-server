-- Vault becomes catalog, which is what this product calls a folder.
--
-- Skills are kept in catalogs, memories are kept in catalogs, and a third word
-- for the same idea is a third thing to learn. "Vault" also promised more than
-- it delivers: what is in one is encrypted, but so is every other secret here,
-- and the folder is not what makes it so.

ALTER TABLE variable_vault RENAME TO variable_catalog;
ALTER TABLE variable_catalog RENAME CONSTRAINT uk_variable_vault_name TO uk_variable_catalog_name;
ALTER INDEX idx_variable_vault_workspace RENAME TO idx_variable_catalog_workspace;

ALTER TABLE workspace_variable RENAME COLUMN vault_id TO catalog_id;
ALTER INDEX idx_workspace_variable_vault RENAME TO idx_workspace_variable_catalog;
