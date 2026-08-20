-- A shell's account is optional, the way it is optional at the ssh command.

-- `ssh build.internal` does not make anybody name a user: leaving it out means
-- "the account I am", and an administrator who types that every day already
-- knows what it means. Requiring one here made the form ask a question that has
-- an obvious answer, and the answer this application can give is the same one -
-- the account this server process runs as. Which account that resolves to is on
-- the Shell page beside the host, so the fallback is something an administrator
-- reads rather than something they discover.
--
-- Null rather than the empty string for "none given", so that the column says
-- which it is. A blank username is not a fallback anywhere in SSH; it is a user
-- name of zero length put on the wire for the far side to refuse, and storing
-- one would make the difference between "not set" and "set to nothing" a thing
-- only the code that wrote it could tell apart.
--
-- Nothing needs backfilling. The column has been NOT NULL since it was made and
-- the service refused a blank one, so every row already holds an account
-- somebody chose, and every one of them keeps working exactly as it did.
ALTER TABLE shell ALTER COLUMN username DROP NOT NULL;
