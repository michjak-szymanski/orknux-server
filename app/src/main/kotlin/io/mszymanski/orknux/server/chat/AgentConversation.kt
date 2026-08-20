package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.model.ChatCompletion
import io.mszymanski.orknux.connector.model.ChatTurn
import io.mszymanski.orknux.connector.model.ModelChatClient
import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.llm.LlmSessionRecorder
import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service

/**
 * Asking an agent something, and letting it use its tools before it answers.
 *
 * A model with tools does not answer in one round: it asks for a lookup, is told
 * what came back, and either asks again or answers. This runs that to a
 * conclusion and hands back the one thing the caller wanted — what the agent
 * finally said.
 *
 * The intermediate turns are deliberately not written to the history. What is
 * kept is the conversation somebody had; that an agent read three skills on the
 * way to an answer is how it worked, not what was said, and putting it in the
 * thread would mean every later round re-reads it and pays for it again.
 *
 * No transaction is held while this runs. It calls a model repeatedly and can
 * take minutes; a database connection held for that long is one nobody else has.
 *
 * A caller that named an LLM session gets the round written down as it happens —
 * the tools that were called, and what was finally said. That is not the same
 * record as the chat history and does not contradict the paragraph above: the
 * history is the conversation somebody had, while a session is the conversation
 * the agent had, working included. A caller that named no session pays for a
 * null check and touches no table at all.
 */
@Service
class AgentConversation(
    private val models: ModelChatClient,
    private val tools: AgentTools,
    private val agents: AgentRepository,
    private val sessions: LlmSessionRecorder,
) {

    /**
     * The same thing, for a caller holding only an id — the streaming endpoint,
     * which is outside the transaction that read the session.
     */
    fun answer(modelId: Long, agentId: Long, turns: List<ChatTurn>, into: Long? = null): ChatCompletion {
        val agent = agents.findByIdOrNull(agentId)
            ?: return ChatCompletion.Failed("That agent no longer exists")
        return answer(modelId, agent, turns, into)
    }

    /**
     * @param turns the conversation so far, briefing included.
     * @param into the LLM session this round is recorded in, or null for a round
     *   nobody is keeping. Null is the ordinary case — a chat keeps its own
     *   history and needs none of this.
     * @return what the agent said, or why it could not say anything.
     */
    fun answer(modelId: Long, agent: Agent, turns: List<ChatTurn>, into: Long? = null): ChatCompletion {
        val offered = tools.specsFor(agent)
        if (offered.isEmpty()) return models.complete(modelId, turns).also { record(into, agent, it) }

        val conversation = turns.toMutableList()
        var spent = 0L

        repeat(MAX_ROUNDS) {
            when (val answer = models.complete(modelId, conversation, offered)) {
                is ChatCompletion.Failed -> return answer.also { record(into, agent, it) }

                is ChatCompletion.Answered ->
                    return answer.copy(millis = spent + answer.millis).also { record(into, agent, it) }

                is ChatCompletion.CalledTools -> {
                    spent += answer.millis
                    conversation += answer.turn
                    answer.calls.forEach { call ->
                        log.debug("Agent {} called {}", agent.name, call.name)
                        // Written before the tool runs, so one that hangs still
                        // leaves the transcript saying what was asked of it.
                        into?.let { sessions.toolCalled(it, call.name, call.arguments) }
                        conversation += ChatTurn(
                            role = "user",
                            content = tools.run(agent, call),
                            respondingTo = call.id,
                        )
                    }
                }
            }
        }

        // Out of rounds. Said plainly rather than returning whatever the last
        // round happened to contain: an agent stuck in a loop of lookups has not
        // answered, and pretending otherwise hides the loop.
        log.warn("Agent {} was still calling tools after {} rounds", agent.name, MAX_ROUNDS)
        return ChatCompletion.Failed(
            "${agent.name} kept looking things up without reaching an answer, and was stopped after $MAX_ROUNDS rounds",
        ).also { record(into, agent, it) }
    }

    /**
     * The round's outcome, written into the session that asked for one.
     *
     * A failure becomes a system note rather than an answer, because it is not
     * something the agent said — it is something that happened to the
     * conversation. A transcript that simply stopped would leave whoever reads
     * it looking for words that were never spoken.
     *
     * A round that asked for tools is not an outcome and is not recorded here;
     * those lines are written as the calls are dispatched, and the round is not
     * over.
     */
    private fun record(into: Long?, agent: Agent, answer: ChatCompletion) {
        val session = into ?: return
        when (answer) {
            is ChatCompletion.Answered -> sessions.agentSaid(session, agent.name, answer.content)
            is ChatCompletion.Failed -> sessions.note(session, "${agent.name} could not answer: ${answer.reason}")
            is ChatCompletion.CalledTools -> Unit
        }
    }

    private companion object {
        /**
         * Enough for a real chain — list, load, look something up, answer — and
         * short enough that a model talking to itself is stopped rather than
         * billed for.
         */
        const val MAX_ROUNDS = 8

        val log = LoggerFactory.getLogger(AgentConversation::class.java)
    }
}
