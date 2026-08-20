-- Whether somebody wants the news desk's items posted to them as well as rung
-- on the bell.
--
-- One column and one answer, rather than a row per kind of news. The bell
-- already decides who hears what - assignee, reporter, observers, and anybody
-- named in a comment - and this says only whether that same list reaches an
-- inbox too. A per-kind table would be a second set of rules about audience,
-- which is the one thing this must not become.
--
-- On by default, because the switch that protects an unprepared installation is
-- ORKNUX_MAIL_HOST: with no relay configured nothing is sent to anybody whatever
-- this column says, and an installation that has deliberately configured one has
-- said it wants to send mail. Defaulting it off would instead mean a feature
-- nobody has until every person finds the Preferences page.
--
-- It applies to somebody with no address too - it simply never comes up, because
-- there is nowhere to write to.
ALTER TABLE app_user
    ADD COLUMN email_notifications BOOLEAN NOT NULL DEFAULT TRUE;
