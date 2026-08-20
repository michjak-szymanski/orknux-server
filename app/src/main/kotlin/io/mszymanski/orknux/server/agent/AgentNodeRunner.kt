package io.mszymanski.orknux.server.agent

import io.mszymanski.orknux.connector.model.ChatCompletion
import io.mszymanski.orknux.connector.model.ChatTurn
import io.mszymanski.orknux.server.chat.AgentBriefing
import io.mszymanski.orknux.server.chat.AgentConversation
import io.mszymanski.orknux.server.llm.LlmSessionKeyTooLongException
import io.mszymanski.orknux.server.llm.LlmSessionRecorder
import io.mszymanski.orknux.server.workflow.NodeExpressions
import io.mszymanski.orknux.workflow.execution.ExecutionStep
import io.mszymanski.orknux.workflow.execution.KIND_RUNNER_ORDER
import io.mszymanski.orknux.workflow.execution.NodeBinding
import io.mszymanski.orknux.workflow.execution.NodeKind
import io.mszymanski.orknux.workflow.execution.NodeRunner
import io.mszymanski.orknux.workflow.execution.LogLevel
import io.mszymanski.orknux.workflow.execution.RunLogger
import io.mszymanski.orknux.workflow.execution.StepFailedException
import io.mszymanski.orknux.workflow.execution.StepResult
import io.mszymanski.orknux.workflow.execution.StepStatus
import org.springframework.data.repository.findByIdOrNull
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode

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
// Ordered like its siblings. Without this it sat where an unannotated bean sits
// — the same place as UnimplementedNodeRunner — so which of the two claimed an
// AGENT node was down to the order the beans happened to be registered in, and
// losing that race means the node is skipped and the run reports success.
@Order(KIND_RUNNER_ORDER)
class AgentNodeRunner(
    private val agents: AgentRepository,
    private val briefing: AgentBriefing,
    private val conversation: AgentConversation,
    private val expressions: NodeExpressions,
    private val runLog: RunLogger,
    private val sessions: LlmSessionRecorder,
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

        val question = prompt ?: input ?: "There is no input for this step. Say what you would do."

        /*
         * The session this node writes into, if it names one.
         *
         * Opened before the turns are built, because what is already in it goes
         * into them: a session that is only written to is a transcript, and what
         * this feature is for is an agent that remembers. The second workflow to
         * compute this key asks its question of a model that has heard the first
         * one's exchange.
         */
        val session = sessionFor(step, agent, mappings, payload, started)

        /*
         * Read before this turn's question is recorded, not after - otherwise
         * the question would come back as part of its own history and the model
         * would be shown it twice.
         */
        val remembered = session?.let { sessions.remembered(it) }.orEmpty()

        val turns = buildList {
            system?.let { add(ChatTurn("system", it)) }
            addAll(remembered)
            add(ChatTurn("user", question))
        }

        /*
         * Written before the model is asked, not after: a run that dies waiting
         * on a model should still leave the question in the transcript, since a
         * session with an answer and no question is worse than one with a
         * question and no answer.
         */
        session?.let { sessions.userSaid(it, step.name, question) }

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

        return when (val answer = conversation.answer(modelId, agent, turns, session)) {
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

    /**
     * The LLM session this node was told to talk into, or null for a node that
     * names none.
     *
     * The two halves are not the agent node's own. They belong to the session
     * node an edge leads from, and `AppWorkflowGraphSource` folds them onto this
     * step when the graph is read for the run — so what arrives here is the
     * session that was wired to this agent at the moment the run started, and
     * redrawing the canvas afterwards cannot change it. Two agents wired to one
     * session node are handed the same two halves, which is how they end up in
     * one conversation without either of them naming it.
     *
     * Two parameters rather than one, because the halves come from different
     * places: the prefix is nearly always something the author typed — the name
     * of the conversation this workflow has — while the key is nearly always
     * read off what arrived, a thread, a ticket, a customer. They are resolved
     * here, against what *this* step was handed, which is what keeps a
     * referenced key reading the run rather than the session node's own view of
     * it. Joining them here is what makes two workflows land in one session on
     * purpose: the same halves, however each of them arrived at them, are the
     * same session.
     *
     * A node with no key records nothing and asks nothing of the database. That
     * is every agent node with no session wired to it, and — because a node that
     * still carries these names from before session nodes existed is left alone
     * unless a session overrides it — every agent node drawn before either
     * existed still behaves exactly as it did.
     */
    private fun sessionFor(
        step: ExecutionStep,
        agent: Agent,
        mappings: Map<String, NodeBinding>,
        payload: JsonNode?,
        started: JsonNode?,
    ): Long? {
        fun resolved(name: String) = mappings[name]
            ?.let { expressions.textOf(it, payload, started) }
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

        val key = resolved(SESSION_KEY) ?: return null

        return try {
            sessions.open(agent.workspaceId, resolved(SESSION_KEY_PREFIX), key)
        } catch (refused: LlmSessionKeyTooLongException) {
            /*
             * Refused rather than trimmed to fit. Two long keys cut to the same
             * length would be one session, and quietly pouring two
             * conversations into one is worse than stopping to say the key
             * cannot be stored — which is a mapping to fix, not a run to retry.
             */
            throw StepFailedException(
                step.nodeKey,
                "${step.name} cannot record its session: ${refused.message}",
                permanent = true,
            )
        }
    }

    private companion object {
        /** What the node asks. Blank or absent leaves the edge's value as the question. */
        const val PROMPT = "prompt"

        /** Replaces the agent's own briefing for this node only. */
        const val SYSTEM_PROMPT = "systemPrompt"

        /**
         * Which conversation this node's turn belongs to, put here by the
         * session node wired to this one. Blank or absent means the turn is not
         * kept anywhere, which is what an agent node with no session does.
         */
        const val SESSION_KEY = "sessionKey"

        /** What the key is namespaced under. Optional; see LlmSessionKey. */
        const val SESSION_KEY_PREFIX = "sessionKeyPrefix"
    }
}
