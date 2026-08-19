-- The model a workspace uses for the small jobs nobody asks for.
--
-- Naming a chat from what was said is the first of them: the work is a model
-- call, but not the one the person is having — so it is set once for the
-- workspace rather than chosen per chat, and a cheap model is the right choice
-- where the chat itself might use an expensive one.
--
-- Null is "no companion", and everything that would use one simply does not
-- happen: a chat keeps the name it was given.
ALTER TABLE workspace ADD COLUMN companion_model_id BIGINT;
