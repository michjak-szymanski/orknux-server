-- Which way out of a condition an edge leaves by.
--
-- Until now an edge carried nothing but its two ends: the graph had no
-- branches, so a condition could only say "carry on" or "there is nothing
-- further to do". That made every alternative path a second workflow, and made
-- a graph read as a straight line whatever it actually meant.
--
-- Null for every edge that is not a branch, which is most of them and all the
-- ones that already exist: an edge from an action to an action is not answering
-- a question. YES and NO are the two answers a condition has.
ALTER TABLE workflow_edge
    ADD COLUMN branch VARCHAR(8);

-- What the two ways out are called on a particular node.
--
-- Defaulted in the interface rather than here, and stored only when somebody
-- changes them: a condition asking "is it urgent" reads better with "Escalate"
-- and "File it" than with Yes and No, and the labels are the whole of what
-- makes the graph readable at a glance.
ALTER TABLE workflow_node
    ADD COLUMN yes_label VARCHAR(40),
    ADD COLUMN no_label VARCHAR(40);
