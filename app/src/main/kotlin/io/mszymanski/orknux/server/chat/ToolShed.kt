package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.model.ToolCall
import io.mszymanski.orknux.connector.model.ToolSpec

/**
 * Tools lent to an agent for one round, by whatever asked it.
 *
 * [AgentTools] is the one place that knows what an *agent* may call, and this is
 * not a second one: what a shed holds is not a capability the agent was granted
 * and not something it has anywhere else. It exists because a caller sometimes
 * needs the model to be able to say something to it that is not text - "I have
 * finished", "I need permission for this", "which of these did you mean" - and
 * the only channel a model has for saying something structured is a tool call.
 *
 * So a shed is a way for the thing running the loop to be addressed by the model
 * inside it, and its lifetime is that loop. Granting through it would be wrong:
 * anything an agent may still call tomorrow belongs on the agent.
 *
 * A shed's names are asked before the agent's own, so a shed cannot be shadowed
 * by a workspace tool that happens to share a name.
 */
interface ToolShed {

    /** What to offer the model, alongside the agent's own. */
    fun specs(): List<ToolSpec>

    /** Whether this is one of the shed's, by exact name. */
    fun handles(name: String): Boolean

    /**
     * Runs one of them.
     *
     * May return text for the model to go on with, or throw [AgentRoundHalted]
     * to end the round there and then — which is what "I have finished" and "I
     * am stuck" both are. An agent's own tools never do either: a tool that
     * failed is a fact the model is told about and the conversation carries on.
     */
    fun run(call: ToolCall): String
}

/**
 * A lent tool ending the round.
 *
 * Not a failure. The caller lent the tool, so the caller knows what it means and
 * catches it; the message is what gets written into the transcript in the tool's
 * place, so it says what happened in words a person reading the log will
 * understand.
 */
open class AgentRoundHalted(note: String) : RuntimeException(note)
