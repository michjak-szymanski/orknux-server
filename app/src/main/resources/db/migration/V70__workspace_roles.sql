-- A workspace is opened by roles, not by the name of a directory group.
--
-- The group string was the identity provider's vocabulary leaking into the model:
-- it only made sense for LDAP, it was free text nobody could validate, and two
-- workspaces meaning the same audience had no way of saying so.
--
-- Every distinct group that was in use becomes a role, and the workspaces that
-- named it are assigned it. Nobody loses access: the role is named after the
-- group's common name, which is exactly what the old check derived its authority
-- from, so a caller who could see a workspace yesterday still can.
CREATE TABLE workspace_role
(
    workspace_id BIGINT NOT NULL REFERENCES workspace (id) ON DELETE CASCADE,
    role_id      BIGINT NOT NULL REFERENCES security_role (id) ON DELETE CASCADE,
    PRIMARY KEY (workspace_id, role_id)
);

-- One role per distinct group, named after its common name: "cn=backend,ou=..."
-- becomes "backend". Case is preserved as written; the unique index on the name is
-- case-insensitive, so two spellings of one group cannot become two roles.
INSERT INTO security_role (name, description, builtin, last_modified_by)
SELECT DISTINCT ON (lower(split_part(ltrim(split_part(w.ldap_group, ',', 1)), '=', 2)))
    split_part(ltrim(split_part(w.ldap_group, ',', 1)), '=', 2),
    'Created from the directory group ' || w.ldap_group || ' when workspaces moved to roles.',
    FALSE,
    'system'
FROM workspace w
WHERE w.ldap_group IS NOT NULL
  AND btrim(w.ldap_group) <> ''
  AND split_part(ltrim(split_part(w.ldap_group, ',', 1)), '=', 2) <> ''
  -- Not one that already exists: an installation may already have made it by hand.
  AND NOT EXISTS (SELECT 1
                  FROM security_role r
                  WHERE lower(r.name) = lower(split_part(ltrim(split_part(w.ldap_group, ',', 1)), '=', 2)));

-- Every role made this way is an ordinary one. Administrators are administrators
-- by holding the built-in role, not by being named on a workspace.
INSERT INTO security_role_scope (role_id, scope)
SELECT r.id, 'USER'
FROM security_role r
WHERE r.builtin = FALSE
  AND NOT EXISTS (SELECT 1 FROM security_role_scope s WHERE s.role_id = r.id);

INSERT INTO workspace_role (workspace_id, role_id)
SELECT w.id, r.id
FROM workspace w
         JOIN security_role r
              ON lower(r.name) = lower(split_part(ltrim(split_part(w.ldap_group, ',', 1)), '=', 2))
WHERE w.ldap_group IS NOT NULL
  AND btrim(w.ldap_group) <> ''
ON CONFLICT DO NOTHING;

-- Kept, not dropped. It is the only record of which directory group granted which
-- role, and that is exactly what the role mapping needs to be written from — an
-- administrator can read it off the old column instead of out of the directory.
-- Nothing reads it any more; a later migration can drop it once mappings are set.
COMMENT ON COLUMN workspace.ldap_group IS
    'Superseded by workspace_role. Kept only so the role mapping can be written from it.';
