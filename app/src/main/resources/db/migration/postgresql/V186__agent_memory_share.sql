-- How much of its model's context window an agent's session may take back.
--
-- What a session puts in front of a model was bounded by five numbers compiled
-- into LlmSessionRecorder: forty turns, twenty-four thousand characters of
-- them, twenty-four tool lookups, sixteen thousand characters of those and
-- eight thousand of any one. Nobody could change any of it without a rebuild,
-- and the numbers were a guess sized against one installation's models and one
-- installation's tools - eight thousand was picked because a workspace's open
-- issues measured under five thousand characters and every issue in it measured
-- over forty, which is a fact about orknux_issues rather than about anybody
-- else's tools.
--
-- The setting is stored as two things because it is two things. A budget is a
-- share of a context window: the window belongs to the model and was already
-- recorded on it - llm_model.context_window, until now written down and never
-- read - and the share belongs to the agent, because what an allowance should
-- be depends on what that agent's tools give back. An agent reading whole files
-- and one reading issue lists can be pointed at the same model and want
-- different answers. So one new column, here, and nothing new on the model.
--
-- Not an ORKNUX_ variable, deliberately. One installation runs models whose
-- windows differ by an order of magnitude, so a single number set for the
-- installation is generous on one of them and a request the provider refuses on
-- the next - which is the problem, not a cheaper spelling of the fix.
--
-- One number rather than five. The other four are derived from it in
-- SessionMemoryBudget, because somebody setting this is answering "how much
-- conversation should it carry" and not five independent questions.
--
-- Null on every row, and null means the built-in default: exactly the five
-- numbers above. An installation that sets nothing behaves today as it did
-- yesterday.
ALTER TABLE agent
    ADD COLUMN memory_share INTEGER;

-- The ceiling is half the window, and the floor is one percent of it.
--
-- Above half there is nothing left for the instructions, the tool declarations,
-- the question and the answer - a value that cannot work, refused where it is
-- set rather than found out at the provider on somebody's turn. The narrower
-- refusals, the ones that need the model's own window and the tokens it
-- reserves for its answer, are in SessionMemoryBudgets: a CHECK cannot read
-- another table, and a share that is fine for a 200k window and impossible for
-- an 8k one is not something one number in this column can decide.
ALTER TABLE agent
    ADD CONSTRAINT ck_agent_memory_share
        CHECK (memory_share IS NULL OR (memory_share >= 1 AND memory_share <= 50));
