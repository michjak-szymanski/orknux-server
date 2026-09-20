package io.mszymanski.orknux.server.agent

import io.mszymanski.orknux.connector.model.ToolCall
import io.mszymanski.orknux.connector.model.ToolParameterSpec
import io.mszymanski.orknux.connector.model.ToolSpec
import io.mszymanski.orknux.server.chat.AgentRoundHalted
import io.mszymanski.orknux.server.chat.ToolShed
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper

/**
 * A way for an agent in a run to say "that was the work" and stop.
 *
 * ### What it is for
 *
 * A round ends when the model writes prose instead of asking for another tool,
 * and that rule assumes the answer *is* the prose. It often is not. An agent
 * answering a Slack mention posts its reply itself, with the connection's own
 * tool, because that is how a file or a picture reaches somebody; by the time
 * the work is done, everything worth saying has been said, in the place it was
 * supposed to be said. The model is then asked for an answer it has no reason
 * to write, and what it does next is one of two bad things: it repeats the
 * message it has already sent, or it answers with nothing at all - which the
 * provider reports as an empty message, which the run reads as a failure, which
 * is retried, which posts the whole thing a second time.
 *
 * So this is the same ending said deliberately. The model calls it, the round
 * ends there, and the step is finished rather than failed.
 *
 * ### Why it is a shed, and why the switch is the other way round
 *
 * It is a shed because it is not a capability. [ToolShed] exists for exactly
 * this - the thing running the loop being addressed by the model inside it, in
 * the one channel a model has for saying something that is not prose - and "I
 * have finished" is the example its own documentation gives. `task_done` is the
 * same idea.
 *
 * And it is on until somebody turns it off, rather than granted by name like a
 * tool. The name-grants are a list of what an agent was *given*: something it
 * could not otherwise reach, ticked by whoever decided it should. An ending
 * reaches nothing and takes nothing; an agent that has to be granted the right
 * to stop is an agent whose turn ends by accident. So it is a switch on the
 * agent - [Agent.finishAccess], the same shape as `artifactAccess` - drawn in
 * the same list as the grants because that list is where somebody looks to see
 * what an agent may do, and ticked there until it is unticked. What it is for
 * is the workflow whose next node needs an answer to work with, where an agent
 * finishing early hands it an empty one.
 *
 * ### What the answer becomes
 *
 * Whatever was passed, and nothing where nothing was passed. A node after this
 * one reads an empty answer, which is the truth: the agent's work went
 * somewhere else, and inventing prose to fill the field would be the run
 * telling the next node something the agent never said.
 *
 * Not offered where the node's answer is held to a shape. A step that has to
 * produce an object cannot be finished without one, and a tool that could only
 * ever be refused is a turn spent teaching the model what the node already
 * knew - the rule `AgentTools` states for every tool.
 */
@Service
class FinishAnswerTools(private val mapper: ObjectMapper) {

    /**
     * The shed for one step, or null where finishing early makes no sense.
     *
     * @param granted whether the agent may finish early. On for every agent
     *   until somebody turns it off, which is the opposite way round from the
     *   name-grants: those are a list of what an agent was given, and an
     *   ending is not something to be given. See [Agent.finishAccess].
     * @param shaped whether the node's answer is held to an object shape. A
     *   step that has to produce an object cannot be finished without one, and
     *   a tool that could only ever be refused is a turn spent teaching the
     *   model what the node already knew.
     */
    fun shed(granted: Boolean = true, shaped: Boolean = false): ToolShed? =
        if (granted && !shaped) Shed() else null

    private inner class Shed : ToolShed {

        override fun specs(): List<ToolSpec> = listOf(FINISHING)

        override fun handles(name: String): Boolean = name == FINISH

        /**
         * Never returns.
         *
         * The only thing this tool does is end the round, and [AgentRoundHalted]
         * is how a shed says so. What it carries is what the step answers with.
         */
        override fun run(call: ToolCall): String =
            throw AnswerFinished(argument(call, "answer").orEmpty().trim())

        private fun argument(call: ToolCall, name: String): String? = runCatching {
            mapper.readTree(call.arguments).path(name).takeIf { it.isTextual }?.stringValue()
        }.getOrNull()
    }

    companion object {

        const val FINISH = "finish_answer"

        val FINISHING = ToolSpec(
            name = FINISH,
            description = "Ends your turn now, without writing an answer. Call it when the work is done " +
                "and the result has already been delivered - a message you posted, a file you uploaded, " +
                "a picture you sent - so there is nothing left to say here. Do not repeat what you have " +
                "already sent; that is what this is for. `answer` is optional and is only for something " +
                "a later step in this workflow needs to read.",
            parameters = listOf(
                ToolParameterSpec(
                    name = "answer",
                    description = "What this step should answer with, for the steps after it. Leave it " +
                        "out where nothing follows or nothing needs it.",
                    required = false,
                ),
            ),
        )
    }
}

/**
 * The agent said the work was done and it had nothing to add.
 *
 * @param answer what the step answers with, which is usually empty. The message
 *   is what the transcript shows in the tool's place, so it reads as an ending
 *   rather than as an error.
 */
class AnswerFinished(val answer: String) : AgentRoundHalted(
    if (answer.isEmpty()) "Finished. The work was delivered, so there is nothing to add." else answer,
)
