-- What a whole chat has cost, kept as a running total on the chat itself.
--
-- The counts have been recorded since there was a chat and thrown away on the
-- way out of it. #227 carried them the last few feet and put them on the answer
-- they belonged to, which is where they belong - and only there: the number
-- came off the stream's last frame, was held in the browser, and was gone the
-- moment the page reloaded. So a chat could say what its newest answer cost and
-- nothing at all about itself. What was asked for is the other number: the
-- whole conversation's, still there when somebody comes back to it.
--
-- **Three columns on `chat_session` rather than a table of turns.**
-- `chat_answer_take` set out the three tests a chat-side table has to pass -
-- not part of the conversation, never put in front of a model, read by nothing
-- but the chat screen - and a per-turn table would pass all three. It would
-- still be the wrong answer, because passing the tests is permission and not a
-- reason. A total is one number, one number is what was asked for, and the
-- burden is on anything more elaborate than the thing being asked for.
--
-- The elaborate version also has a question to answer that this one does not.
-- A per-turn row has to say which turn it is, and the only address a turn has
-- is its position in the thread - which `chat_answer_take` and
-- `chat_message_thinking` both use, and which a regenerate makes ambiguous
-- here in a way it is not there: two answers were given at one position and
-- both were paid for, so the position is no longer a key. A running total has
-- no such problem, because addition does not care what order it happens in.
--
-- And the per-turn record already exists. `model_usage_day` has held every
-- call's counts all along, per model per day; it is the wrong grain for a chat
-- and the right grain for an invoice, and anybody auditing what a provider
-- charged reads that rather than this.
--
-- **Tokens, and not money.** He asked for tokens, and tokens are also the only
-- figure that survives being stored. A price is the model's and models are
-- repriced; a total accumulated at yesterday's rate is a number nobody can
-- reproduce from the prices they can see, and the arithmetic behind it is gone.
-- Pricing a stored token total at read time has the opposite problem in a
-- smaller size - a chat may be moved onto another model mid-conversation, and
-- costing the whole total at whichever model answers now would be arithmetic
-- nobody can check either. So the total says tokens, which is true whatever a
-- model costs, and the per-answer line #227 built keeps the money, where the
-- model that answered and the prices it carried are both the ones in front of
-- you.
--
-- **A regenerated answer counts twice, because it was paid for twice.** #245
-- keeps the answer a regenerate displaced, as a take, on the ground that it was
-- really said. It was also really billed. A total that counted only the answer
-- still standing would be a total that goes *down* when somebody presses a
-- button that spends money, which is the one thing a bill may never do.
--
-- **Zero is not shown, and that is the point of it being zero.** An existing
-- chat gets nought here, and so does one where the provider reported no counts
-- - the same two silences #227 settled, plus a chat nobody has spoken in yet.
-- All three read the same from the screen and the screen says nothing at all,
-- rather than claiming a conversation cost nothing. There is one seam and it is
-- worth naming: a chat that was spoken in before this migration counts from its
-- next turn on, so its total is short by whatever came before. The alternative
-- was to mark those chats and never total them at all, which would ship the
-- feature to nobody who already uses the product.
ALTER TABLE chat_session
    ADD COLUMN spent_input_tokens BIGINT NOT NULL DEFAULT 0;

ALTER TABLE chat_session
    ADD COLUMN spent_output_tokens BIGINT NOT NULL DEFAULT 0;

-- Pictures, counted rather than costed in tokens.
--
-- An image model charges per picture and reports no tokens at all, so a drawn
-- picture would add nought to the two columns above and read as a turn that was
-- free. It was not free. It is counted here instead and said beside the tokens
-- rather than folded into them, because a picture and a token are not the same
-- unit and adding them would only be a way of hiding that.
ALTER TABLE chat_session
    ADD COLUMN spent_pictures INTEGER NOT NULL DEFAULT 0;
