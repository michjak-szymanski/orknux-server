-- An address for the people this installation knows.
--
-- The directory already says one - LDAP's inetOrgPerson carries mail, and an
-- OIDC token carries an email claim - but it was read off the principal at
-- sign-in and thrown away with the session. Nothing outside the person's own
-- browser could ever answer "where do we write to alice", which is a poor
-- position for an installation that sends mail.
--
-- Nullable, because a directory need not supply one and because every row that
-- already exists has none. An address nobody knows is better recorded as
-- nothing than guessed at.
ALTER TABLE app_user
    ADD COLUMN email VARCHAR(320);

-- Whether the address above was typed here or inherited from the provider.
--
-- Needed because sign-in refreshes what the directory says, and somebody who
-- has typed their own address should not have it quietly replaced every time
-- they arrive. One flag rather than a second column holding the provider's
-- value: the only question ever asked is "may sign-in overwrite this", and
-- keeping a shadow copy of an address nobody reads would be a second answer to
-- "what is alice's address". Clearing a chosen address puts this back to false,
-- so the next sign-in seeds it again.
ALTER TABLE app_user
    ADD COLUMN email_chosen BOOLEAN NOT NULL DEFAULT FALSE;
