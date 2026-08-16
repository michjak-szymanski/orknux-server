-- The directory group whose members may see the team, e.g.
-- cn=backend,ou=teams,dc=orknux,dc=io. Null means nobody but administrators.
ALTER TABLE team
    ADD COLUMN ldap_group VARCHAR(255);
