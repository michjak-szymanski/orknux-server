-- Which model an agent thinks with.
--
-- Null is "none chosen", and an agent without one cannot be run: the alternative
-- is falling back to some workspace default, which is a decision made on
-- somebody's behalf about the thing that costs money and changes the answers.
ALTER TABLE agent ADD COLUMN model_id BIGINT;
