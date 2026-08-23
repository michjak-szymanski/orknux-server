-- How voice mode decides somebody has finished talking, per workspace.
--
-- The microphone ended people's turns while they were still speaking. It was
-- reported three times in the same session - "I cannot finish my sentence",
-- "it stops listening and sends message", and after a first fix "no, it's not
-- silent, I was still talking" - and each fix was a better guess at one number
-- that suits one voice, one room and one microphone. It is not a number that
-- can be got right centrally: how long somebody pauses mid-sentence, how loud
-- their room is and how far they sit from the microphone are facts about them,
-- so the answer is to let a workspace say.
--
-- Three columns and no defaults. Null means the workspace has decided nothing
-- and voice mode uses its own value, which is what every existing row does and
-- goes on doing. Deliberately no DEFAULT clause and no value written down here:
-- what a workspace that has decided nothing gets belongs to the interface,
-- which is the half that can judge it, and a copy on this side would be a
-- second source of truth that drifts the first moment either of them moves.
-- The units are the interface's own too, so nothing converts at the boundary.
ALTER TABLE workspace
    ADD COLUMN voice_pause_ends_turn_ms INTEGER;

ALTER TABLE workspace
    ADD COLUMN voice_speech_over_room_percent INTEGER;

ALTER TABLE workspace
    ADD COLUMN voice_unattended_microphone_ms INTEGER;

-- The pause that ends a turn: one and a half to ten seconds.
--
-- The floor sits strictly above 1.2 seconds, which is the value demonstrated to
-- cut people off at clause breaks - people stop to think in the middle of a
-- sentence, and every one of those stops was read as their turn ending. Putting
-- the floor above it is what makes the reported bug unreachable through
-- configuration rather than merely fixed once. Ten seconds is the ceiling
-- because past it nothing happening stops reading as patience and starts
-- reading as the application being broken.
ALTER TABLE workspace
    ADD CONSTRAINT ck_workspace_voice_pause_ends_turn
        CHECK (voice_pause_ends_turn_ms IS NULL OR (voice_pause_ends_turn_ms BETWEEN 1500 AND 10000));

-- How far above the room a voice has to stand: 1.2 to 6 times it.
--
-- A ratio rather than a loudness, because speech is several times the level of
-- whatever room it is spoken in, so this travels between microphones where a
-- fixed level does not. Below about 1.2 it cannot separate the two at all and
-- the failure inverts: a breath or a keystroke clears the line, the turn is
-- held open and never ends. Above 6 you have to raise your voice, which is the
-- complaint this exists to answer.
ALTER TABLE workspace
    ADD CONSTRAINT ck_workspace_voice_speech_over_room
        CHECK (voice_speech_over_room_percent IS NULL OR (voice_speech_over_room_percent BETWEEN 120 AND 600));

-- The fuse on an open microphone: five minutes to an hour.
--
-- Thirty seconds and two minutes both looked like defensible limits on a turn,
-- and both cut the same person off in the middle of a sentence - so the floor
-- has to be well clear of a long spoken thought rather than merely above
-- whatever failed last. This is not a limit on how much anybody may say: the
-- pause above is what ends a turn, and this fires only where no pause occurred
-- in the whole span, which is extraordinary for a person and ordinary for a
-- microphone left open in an empty room. An hour is the ceiling because a fuse
-- that never blows is not a fuse.
ALTER TABLE workspace
    ADD CONSTRAINT ck_workspace_voice_unattended_microphone
        CHECK (voice_unattended_microphone_ms IS NULL OR (voice_unattended_microphone_ms BETWEEN 300000 AND 3600000));
