-- What a tool gave back, kept beside what it was asked.
--
-- Until now only the call was recorded, on the stated ground that the result
-- was "already threaded back into the conversation the model sees". That is
-- true inside one exchange and false across two. Spring AI resolves a round of
-- tool calls inside its own loop and stores only the assistant text that came
-- out of it, so on the next turn the model's context holds its own summary of
-- what a tool returned and not the thing itself - and "check that again" is
-- answered out of the summary. It was found in a chat where two models running
-- one conversation both reported issues as unlabelled that plainly carried a
-- label, each correcting itself the moment it called the tool again.
--
-- A second column on the same row rather than a fifth kind of event. The result
-- is not a fifth speaker - it is the tool's, exactly as the call is - and a row
-- that holds both is a pairing nothing has to reconstruct: written as two rows
-- they could only be matched by order, and a round of parallel calls is written
-- inside one millisecond with nothing but the id to tell the members apart.
--
-- Null on every row that is not a TOOL, and null on a call whose tool never
-- returned - which is a state worth being able to see, since the call is
-- deliberately written before the tool runs so that one which hangs still
-- leaves the transcript saying what was asked of it.
--
-- TEXT for the same reason `content` is: a listing of every issue in a
-- workspace measured forty thousand characters, and there is no honest limit to
-- write down. What a model is allowed to read back is bounded by
-- LlmSessionRecorder rather than by the column, because the bound belongs to
-- the prompt and not to the record.
ALTER TABLE llm_session_event ADD COLUMN result TEXT;

-- The recall reads one session's calls, newest first, and only those that
-- returned something. Without this it is a scan of every event in the session
-- on every turn of every conversation that has an agent in it.
CREATE INDEX llm_session_event_result_idx
    ON llm_session_event (session_id, at DESC, id DESC)
    WHERE result IS NOT NULL;
