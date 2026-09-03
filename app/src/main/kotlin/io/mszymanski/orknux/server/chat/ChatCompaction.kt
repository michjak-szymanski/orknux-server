package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.model.ChatTurn
import io.mszymanski.orknux.connector.model.ChatCompletion
import io.mszymanski.orknux.connector.model.ModelChatClient
import io.mszymanski.orknux.server.workspace.Workspace
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.memory.ChatMemoryRepository
import org.springframework.stereotype.Service

/**
 * Keeps a long chat inside the window the model will accept.
 *
 * A conversation that outgrows its model fails on the next turn, and it fails
 * with a number: *this model's maximum context length is 128000 tokens*. That is
 * true and useless — the person reading it wanted to keep talking, and the only
 * thing they can do about it is start again and lose everything. Issue #286.
 *
 * So above a threshold the older part of the thread is replaced by one summary
 * of itself and the chat carries on. What is kept is the recent end, verbatim:
 * the last few turns are what the next answer is actually about, and summarising
 * those would be summarising the question being asked.
 *
 * **The summary replaces the messages, and they are gone.** That is the point of
 * it — a copy kept beside them would be the same conversation with something
 * added, which is the opposite of compacting. It is why this is off until
 * somebody turns it on, and why the threshold is theirs to set rather than
 * guessed from the model.
 *
 * Tokens are estimated rather than counted. The exact number depends on the
 * model's own tokeniser, which this server does not have and should not carry
 * one per provider of; four characters to a token is the usual rule of thumb and
 * is close enough for a threshold somebody chose approximately anyway. It errs
 * high — see [tokensIn] — because compacting a little early costs one summary
 * and compacting a little late costs the turn.
 */
@Service
class ChatCompaction(
    private val history: ChatMemoryRepository,
    private val models: ModelChatClient,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Compacts this conversation if the workspace asked for it and it has grown
     * past the line.
     *
     * Called before a turn is built rather than after one lands, so the turn
     * that would have overflowed is the one that fits. Answers whether anything
     * was done, which is what the caller says out loud.
     */
    fun compactIfNeeded(workspace: Workspace, conversationId: String, fallbackModelId: Long?): Boolean {
        val threshold = workspace.compactAfterTokens?.takeIf { it > 0 } ?: return false

        val thread = history.findByConversationId(conversationId)
        if (thread.size <= KEEP + 1) return false

        val carried = tokensIn(thread)
        if (carried < threshold) return false

        val older = thread.dropLast(KEEP)
        val recent = thread.takeLast(KEEP)

        val modelId = workspace.compactionModelId ?: fallbackModelId ?: return false
        val summary = summarise(modelId, older, workspace.compactionSummaryTokens ?: DEFAULT_SUMMARY_TOKENS)
            ?: return false

        /*
         * Written as an assistant turn rather than a system one. A system
         * message is an instruction, and this is not one: it is what was said,
         * shorter. Models treat the two differently, and one that took a summary
         * of a conversation as an instruction would start following it.
         */
        history.saveAll(conversationId, listOf(AssistantMessage(summary)) + recent)
        log.info(
            "Compacted conversation {}: {} messages ({} tokens) became a summary and the last {}",
            conversationId,
            older.size,
            carried,
            KEEP,
        )
        return true
    }

    /**
     * The older turns, as one paragraph.
     *
     * Null when the model would not answer, and the caller then leaves the
     * thread alone: a chat that goes on being too long is a worse outcome than
     * one that fails to send, but silently throwing the older half away because
     * the summariser was unreachable is worse than both.
     */
    private fun summarise(modelId: Long, older: List<Message>, budget: Int): String? {
        val transcript = older.joinToString("\n\n") { "${role(it)}: ${it.text.orEmpty()}" }
        val asked = listOf(
            ChatTurn("system", BRIEF.format(budget)),
            ChatTurn("user", transcript),
        )

        return when (val said = runCatching { models.complete(modelId, asked) }.getOrNull()) {
            is ChatCompletion.Answered -> said.content.trim().takeIf { it.isNotBlank() }
            else -> {
                log.warn("A conversation could not be compacted: the summariser did not answer")
                null
            }
        }
    }

    private fun role(message: Message): String =
        if (message is AssistantMessage) "assistant" else "user"

    /**
     * Roughly how many tokens a thread comes to.
     *
     * Four characters to a token, rounded up, and it is meant to read a little
     * high: compacting early costs one summary, compacting late costs the turn
     * somebody was in the middle of.
     */
    private fun tokensIn(thread: List<Message>): Int =
        thread.sumOf { (it.text.orEmpty().length + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN }

    private companion object {
        /**
         * How many turns are kept word for word.
         *
         * The recent end is what the next answer is about, and summarising the
         * question being asked is how a chat starts answering something adjacent
         * to what was said.
         */
        const val KEEP = 6

        const val CHARS_PER_TOKEN = 4

        /** Long enough to be worth having, short enough to be a compaction. */
        const val DEFAULT_SUMMARY_TOKENS = 500

        /**
         * What the summariser is told.
         *
         * It asks for the things a conversation is resumed from - what was
         * decided, what is outstanding, what the person is like to talk to - and
         * says plainly that this replaces the transcript, because a summariser
         * that thinks it is writing an abstract writes about the conversation
         * instead of continuing it.
         */
        const val BRIEF = """
            You are compacting a conversation so it can carry on. What you write replaces
            the transcript below: whatever you leave out is gone.

            Keep what a person picking this up would need - what was asked, what was
            decided, what is still open, names, numbers and any instruction that still
            stands. Drop pleasantries and anything already superseded.

            Write it as notes in the third person, under %d tokens. Do not address anyone,
            do not describe the conversation, and do not add anything that was not said.
        """
    }
}
