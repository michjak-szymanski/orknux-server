-- A secret typed into a plugin parameter, kept where a secret belongs.
--
-- Until now a parameter the plugin declared secret could only be answered by
-- pointing at a workspace variable, and the refusal said why: what is typed
-- into a parameter is stored as typed and shown back on the page. That is a
-- fact about the column rather than about secrets, so this is a column that is
-- not - encrypted through SecretConverter, exactly as a connection's
-- credential is, and never returned to any screen.
--
-- Pointing at a variable stays the better answer wherever one credential
-- serves more than one thing: it is shared, owned, has a history, and rotating
-- it is one edit rather than four. This is for the other case, where making a
-- variable to hold one plugin's one token was a step that bought nothing.
alter table plugin_parameter
    add column secret_value varchar(4096);

-- And the rule becomes at most one source rather than one of two.
--
-- All three null stays legal for the reason the original note gives: a
-- reference whose variable was deleted becomes exactly that, and it has to be
-- storable or the delete would fail instead.
alter table plugin_parameter
    drop constraint plugin_parameter_one_source;

alter table plugin_parameter
    add constraint plugin_parameter_one_source CHECK (
        (case when literal_value is null then 0 else 1 end)
        + (case when secret_value is null then 0 else 1 end)
        + (case when variable_id is null then 0 else 1 end) <= 1
    );
