-- Roles that administer a workspace, as opposed to roles that merely open it.
--
-- A workspace already has a set of roles that open it. This is a second set, and
-- the two together are what "workspace administrator" means here: holding a role
-- in this set lets somebody change that workspace's name and description, put
-- other people on its issues as observers, and move an issue in or out of it -
-- and it says nothing at all about any other workspace.
--
-- Per workspace rather than one installation-wide "workspace admins" role,
-- because the name promises the smaller thing. A single role that administers
-- every workspace its holder can see is not per-workspace at all, and the first
-- time somebody should lead one team's tracker and only work in another's it
-- would have to become this table anyway - with the difference that by then
-- there would be holders to migrate.
--
-- A second table rather than a flag on workspace_role. Both are join tables from
-- a workspace to a role, both are read by the same access check, and a flag
-- would have made the existing table an entity with an identity of its own for
-- the sake of one boolean. Being a separate table also means the invariant is
-- visible: what is in here is meant to be a subset of workspace_role, which the
-- API enforces on save, and a row here that is missing there is a role that
-- administers a workspace it cannot see.
--
-- Nothing is granted by this migration. An installation upgrades with no
-- workspace administrators anywhere, which is exactly what it had yesterday:
-- installation administrators administer every workspace without being named
-- here, and everybody else is unchanged until somebody assigns a role.
CREATE TABLE workspace_admin_role
(
    workspace_id BIGINT NOT NULL REFERENCES workspace (id) ON DELETE CASCADE,
    role_id      BIGINT NOT NULL REFERENCES security_role (id) ON DELETE CASCADE,
    PRIMARY KEY (workspace_id, role_id)
);
