package io.mszymanski.orknux.server.task

import io.mszymanski.orknux.connector.model.ToolCall
import io.mszymanski.orknux.connector.model.ToolParameterSpec
import io.mszymanski.orknux.connector.model.ToolSpec
import io.mszymanski.orknux.server.chat.AgentRoundHalted
import io.mszymanski.orknux.server.chat.ToolShed
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper

/**
 * The three things an agent can say to the task it is working on.
 *
 * They are tools rather than a convention about what the model writes, because a
 * tool call is the only channel a model has for saying something structured. "I
 * have finished" written in prose is a sentence somebody has to parse and will
 * eventually parse wrongly; `task_done` is a fact.
 *
 * Two of the three park the task, and they park it the same way on purpose: a
 * question about how to deliver something and a request for permission are one
 * mechanism seen from two angles. Stop, write down what is being asked, tell
 * whoever should hear about it, and come back with exactly what they said.
 */
@Service
class TaskTools(private val mapper: ObjectMapper) {

    /**
     * The shed for one task's round.
     *
     * It writes nothing. What a park has to record is written by the loop that
     * catches [TaskParked], because the loop is what owns the task's row and its
     * transaction — a tool that wrote its own would be writing from inside a
     * model call that holds none.
     */
    fun shed(): ToolShed = Shed()

    private inner class Shed : ToolShed {

        override fun specs(): List<ToolSpec> = SPECS

        override fun handles(name: String): Boolean = name in NAMES

        override fun run(call: ToolCall): String = when (call.name) {
            DONE -> throw TaskFinished(argument(call, "summary").orEmpty().ifBlank { "The work is finished." })

            ASK -> {
                val question = argument(call, "question")
                if (question.isNullOrBlank()) {
                    refuse("Say what you want to know: task_ask takes a question.")
                } else {
                    park(TaskRequestKind.QUESTION, null, null, question)
                }
            }

            PERMISSION -> {
                val asked = argument(call, "capability")
                val capability = TaskCapability.entries.firstOrNull { it.name.equals(asked?.trim(), true) }
                val name = argument(call, "name")?.trim()
                val why = argument(call, "why")?.trim()
                when {
                    capability == null -> refuse(
                        "There is nothing called $asked to ask for. It is one of: " +
                            TaskCapability.entries.joinToString { it.name.lowercase() } + ".",
                    )

                    capability.named && name.isNullOrBlank() -> refuse(
                        "Asking for ${capability.name.lowercase()} means naming one; pass its name.",
                    )

                    why.isNullOrBlank() -> refuse(
                        "Say why you need it. Somebody has to read that before deciding.",
                    )

                    else -> park(TaskRequestKind.PERMISSION, capability, name.takeIf { capability.named }, why)
                }
            }

            else -> refuse("There is no tool called ${call.name}")
        }

        private fun park(
            kind: TaskRequestKind,
            capability: TaskCapability?,
            subject: String?,
            asks: String,
        ): String = throw TaskParked(kind, capability, subject, asks)

        private fun refuse(why: String): String = mapper.writeValueAsString(mapOf("error" to why))

        private fun argument(call: ToolCall, name: String): String? = runCatching {
            mapper.readTree(call.arguments).path(name).stringValue()
        }.getOrNull()
    }

    private companion object {
        const val DONE = "task_done"
        const val ASK = "task_ask"
        const val PERMISSION = "task_request_permission"

        val NAMES = setOf(DONE, ASK, PERMISSION)

        val SPECS = listOf(
            ToolSpec(
                name = DONE,
                description =
                    "Call this when the task is finished and there is nothing further to do. Say in the summary " +
                        "what you did and where the result is - that summary is what whoever asked for this reads, " +
                        "and it is the last thing you will say. Do not call it to report progress: anything you " +
                        "write without calling a tool is recorded as progress and you will be asked to carry on.",
                parameters = listOf(
                    ToolParameterSpec("summary", "What you did, and where the result is.", required = true),
                ),
            ),
            ToolSpec(
                name = ASK,
                description =
                    "Stops and asks whoever is responsible for this task a question, then waits for the answer. " +
                        "Use it when you cannot sensibly go on without knowing something - most often when the " +
                        "prompt does not say how the thing you are producing should be delivered. It may be hours " +
                        "before you are answered, and you will be told what they said. Ask one clear question; " +
                        "guessing is worse than waiting, and asking about something the prompt already answers is " +
                        "worse than either.",
                parameters = listOf(
                    ToolParameterSpec("question", "What you need to know, in one question.", required = true),
                ),
            ),
            ToolSpec(
                name = PERMISSION,
                description =
                    "Stops and asks for something you have not been given, then waits for a decision. " +
                        "`capability` is one of: orknux (asking this application about itself and starting " +
                        "workflows), shells (opening a session on one of this installation's machines and running " +
                        "commands), tool (one of this workspace's own tools), mcp_server, skill_catalog, " +
                        "memory_catalog. The last four name one thing, which goes in `name`. Say in `why` what you " +
                        "need it for: a person reads that and decides, and they are deciding for this task only.",
                parameters = listOf(
                    ToolParameterSpec(
                        "capability",
                        "orknux, shells, tool, mcp_server, skill_catalog or memory_catalog.",
                        required = true,
                    ),
                    ToolParameterSpec("name", "Which one, for the four that name something.", required = false),
                    ToolParameterSpec("why", "What you need it for.", required = true),
                ),
            ),
        )
    }
}

/**
 * The agent said it had finished.
 *
 * @param summary what it wants whoever asked to read, which becomes the task's
 *   outcome.
 */
class TaskFinished(val summary: String) : AgentRoundHalted(summary)

/**
 * The agent stopped to ask for something.
 *
 * Carries the whole of what was asked, because the loop that catches it is what
 * writes it down: the round it came out of holds no transaction, and a request
 * written from inside a model call would be one written before anybody knew
 * whether the round survived.
 */
class TaskParked(
    val kind: TaskRequestKind,
    val capability: TaskCapability?,
    val subject: String?,
    val asks: String,
) : AgentRoundHalted(
    when (kind) {
        TaskRequestKind.QUESTION -> "Waiting for an answer: $asks"
        TaskRequestKind.PERMISSION -> "Waiting for permission: ${capability?.name?.lowercase()}" +
            (subject?.let { " $it" } ?: "") + " - $asks"
    },
)
