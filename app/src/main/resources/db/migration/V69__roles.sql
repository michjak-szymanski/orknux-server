-- Roles this installation defines, as opposed to groups a directory happens to have.
--
-- Access has so far been decided by whatever the identity provider called its
-- groups: a workspace stored an LDAP group DN, and an authority derived from its
-- common name had to match. That works for exactly one provider. With a second
-- coming, the thing being matched has to belong to this application — a provider's
-- group or an OIDC claim is then mapped to one of these, and everything downstream
-- only ever deals in roles.
--
-- Nothing changes yet. This adds the roles and the built-in one; workspaces still
-- use their LDAP group until the migration that moves them over.
CREATE TABLE security_role
(
    id               BIGSERIAL PRIMARY KEY,
    name             VARCHAR(120) NOT NULL,
    description      VARCHAR(500),

    -- The one role that is not somebody's to change. An installation with no
    -- administrator role is an installation nobody can administer, and a delete
    -- button that can do that is a delete button that eventually will.
    builtin          BOOLEAN      NOT NULL DEFAULT FALSE,

    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_modified_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_modified_by VARCHAR(120) NOT NULL DEFAULT 'system'
);

-- Named, not keyed: the name is what an administrator picks in a list, what a
-- workspace is assigned, and what the configuration maps a provider's group onto.
-- One spelling everywhere beats an id in the file and a name on the screen.
CREATE UNIQUE INDEX ux_security_role_name ON security_role (lower(name));

-- What a role lets somebody do beyond the workspaces it is assigned to. A set
-- rather than a column, because a role is a bundle of permissions and today's two
-- are not going to stay two.
CREATE TABLE security_role_scope
(
    role_id BIGINT      NOT NULL REFERENCES security_role (id) ON DELETE CASCADE,
    scope   VARCHAR(16) NOT NULL,
    PRIMARY KEY (role_id, scope),
    CONSTRAINT ck_security_role_scope CHECK (scope IN ('ADMIN', 'USER'))
);

INSERT INTO security_role (name, description, builtin)
VALUES ('Administrators',
        'Sees the Admin section and every workspace, whatever else is assigned. Built in: this role cannot be edited or removed.',
        TRUE);

INSERT INTO security_role_scope (role_id, scope)
SELECT id, 'ADMIN'
FROM security_role
WHERE builtin;
