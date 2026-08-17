package io.mszymanski.orknux.server.agent

import io.mszymanski.orknux.connector.model.ChatCompletion
import io.mszymanski.orknux.connector.model.ChatTurn
import io.mszymanski.orknux.server.chat.AgentBriefing
import io.mszymanski.orknux.server.chat.AgentConversation
import io.mszymanski.orknux.server.workflow.NodeExpressions
import io.mszymanski.orknux.workflow.execution.ExecutionStep
import io.mszymanski.orknux.workflow.execution.NodeKind
import io.mszymanski.orknux.workflow.execution.NodeRunner
import io.mszymanski.orknux.workflow.execution.LogLevel
import io.mszymanski.orknux.workflow.execution.RunLogger
import io.mszymanski.orknux.workflow.execution.StepFailedException
import io.mszymanski.orknux.workflow.execution.StepResult
import io.mszymanski.orknux.workflow.execution.StepStatus
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component

/**
 * Runs an agent node: asks the agent what it makes of what reached it.
 *
 * The same runtime a chat with an agent uses, and deliberately so. An agent is
 * one configuration — a model, instructions, granted catalogs — and it should
 * behave the same whether somebody is talking to it or a workflow is. Anything
 * else means two agents with one name, and a difference nobody can see until it
 * matters.
 *
 * What the node is handed becomes the question, and what the agent says becomes
 * the step's output, so the node after it is handed an answer the way it would
 * be handed a function's return value.
 */
@Component
class AgentNodeRunner(
    private val agents: AgentRepository,
    private val briefing: AgentBriefing,
    private val conversation: AgentConversation,
    private val expressions: NodeExpressions,
    private val runLog: RunLogger,
) : NodeRunner {

    override fun supports(kind: NodeKind): Boolean = kind == NodeKind.AGENT

    override fun run(step: ExecutionStep, input: String?, trigger: String?): StepResult {
        // A node pointing at nothing is not configured, which is not a failure:
        // a graph is drawn before it is finished, and a run should say what it
        // found rather than stopping the workflow over it.
        val agentId = step.agentId
            ?: return StepResult(StepStatus.SKIPPED, "${step.name} names no agent, so there was nothing to ask.")

        val agent = agents.findByIdOrNull(agentId)
            ?: return StepResult(StepStatus.SKIPPED, "The agent ${step.name} runs has been deleted.")
        if (!agent.enabled) {
            return StepResult(StepStatus.SKIPPED, "${agent.name} is not active, so it was not asked.")
        }

        // A model is the one thing it cannot do without, and being told so is
        // more useful than an empty answer.
        val modelId = agent.modelId
            ?: throw StepFailedException(step.nodeKey, "${agent.name} has no model chosen, so it cannot answer")

        /*
         * The node's own wording, if it was given any.
         *
         * Without this the agent is asked whatever arrived along the edge,
         * verbatim — which for a Slack trigger means being handed the raw event
         * JSON and left to work out what the question was. A prompt mapping is
         * where the question gets asked — either wording of your own, or the
         * field carrying what came in.
         *
         * Both fall back to what they did before, so a node drawn before this
         * existed runs exactly as it did.
         */
        val payload = expressions.parse(input)
        val started = expressions.parse(trigger)
        val mappings = expressions.mappingsOf(step)

        val prompt = mappings[PROMPT]
            ?.let { expressions.textOf(it, payload, started) }
            ?.takeIf { it.isNotBlank() }

        val system = mappings[SYSTEM_PROMPT]
            ?.let { expressions.textOf(it, payload, started) }
            ?.takeIf { it.isNotBlank() }
            ?: briefing.of(agent)

        val turns = buildList {
            system?.let { add(ChatTurn("system", it)) }
            add(ChatTurn("user", prompt ?: input ?: "There is no input for this step. Say what you would do."))
        }

        /*
         * Said before the model is asked, not after.
         *
         * A model can take a minute, and until it answers the run's log showed
         * nothing at all for this node — indistinguishable from a step that had
         * stalled. This is the line that says the wait is the model thinking.
         */
        runLog.write(
            step.executionId,
            step.nodeKey,
            LogLevel.INFO,
            "${agent.name} is thinking — waiting on the model",
        )

        return when (val answer = conversation.answer(modelId, agent, turns)) {
            // Named, the answer is handed on as an object holding it, so the next
            // node can refer to it by that name. Prose has no fields, and a
            // node cannot refer to something that has no name.
            is ChatCompletion.Answered -> StepResult(StepStatus.COMPLETED, expressions.named(step.outputName, answer.content))
            is ChatCompletion.Failed -> throw StepFailedException(step.nodeKey, "${agent.name} could not answer: ${answer.reason}")
            // The loop runs tools to a conclusion, so nothing here is still
            // asking for one.
            is ChatCompletion.CalledTools ->
                throw StepFailedException(step.nodeKey, "${agent.name} asked for a tool that could not be run")
        }
    }

    private companion object {
        /** What the node asks. Blank or absent leaves the edge's value as the question. */
        const val PROMPT = "prompt"

        /** Replaces the agent's own briefing for this node only. */
        const val SYSTEM_PROMPT = "systemPrompt"
    }
}
