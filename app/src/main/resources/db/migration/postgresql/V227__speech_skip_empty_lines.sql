-- A speech model that is not read the blank lines.

-- A blank line is a thing the eye reads and the ear cannot. Readers differ on
-- what to do with one: some pause for an uncomfortably long time, some treat it
-- as the end of the utterance and clip what follows, and some read the answer's
-- shape as hesitation that was never in the words. Which of those happens is
-- the reader's business, and detecting it is not worth attempting - so this is
-- a switch somebody sets once against the model they are actually using.
--
-- On the model rather than on the workspace because it is a fact about the
-- reader. The same answer sent to two speech models wants this on for one and
-- off for the other, and a workspace setting would make somebody choose for
-- both at once.
--
-- FALSE for every row, and for every model made after this. Reading an answer
-- aloud is the sort of thing somebody has already tuned to their own ear, and a
-- default that changed how it sounds would be this deciding that their tuning
-- was wrong. Not being read the blank lines is the deliberate choice, so it is
-- the one somebody has to make.
--
-- NOT NULL rather than nullable-means-no: there is no third state here, the
-- lines are either taken out or they are not, and a null would have to be read
-- as one of the two anyway.
--
-- Nothing about where an answer is cut. The cuts are made in the browser before
-- any of this, and under paragraph chunking they are made *on* these lines;
-- this only governs the text handed to the reader once a piece has been decided.

ALTER TABLE llm_model ADD COLUMN speech_skip_empty_lines BOOLEAN NOT NULL DEFAULT FALSE;
