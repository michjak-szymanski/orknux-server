-- Where an answer is cut for the speech provider, per workspace.
--
-- Reading an answer aloud is pipelined: a piece is asked for, played, and the
-- next is made while it is in the air. That was one shape for everybody, cut at
-- sentence ends, and it is a listening preference rather than a fact - somebody
-- in a hands-free conversation wants the first word as early as it can be had,
-- and somebody listening to a written answer hears the joins between clips
-- instead. Neither is wrong, so the workspace says which.
--
-- Unlike the three turn-taking columns beside it this takes a value rather than
-- a null. Those store a departure from a number the interface owns, and null is
-- how a workspace says it has decided nothing; this stores one of three named
-- things to ask for, and the middle one is on the list by name. "The default"
-- as a fourth choice would be a second spelling of SENTENCE, and a form
-- offering both would have to say which of the two it saved.
--
-- SENTENCE by default, which is what every existing row is doing today, so no
-- installation is read to differently for having been upgraded.
ALTER TABLE workspace
    ADD COLUMN voice_speech_chunking VARCHAR(16) NOT NULL DEFAULT 'SENTENCE';

-- The three, and nothing else. The size of a sentence-cut piece is not here on
-- purpose: a mode and a size is two knobs describing one thing, and the ceiling
-- that holds a piece to about a breath belongs to the interface that plays it.
ALTER TABLE workspace
    ADD CONSTRAINT ck_workspace_voice_speech_chunking
        CHECK (voice_speech_chunking IN ('NONE', 'SENTENCE', 'PARAGRAPH'));
