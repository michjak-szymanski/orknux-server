package io.mszymanski.orknux.server.task

import io.mszymanski.orknux.connector.model.ToolCall
import io.mszymanski.orknux.connector.model.ToolParameterSpec
import io.mszymanski.orknux.connector.model.ToolSpec
import io.mszymanski.orknux.server.chat.AgentRoundHalted
import io.mszymanski.orknux.server.chat.ToolShed
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper

/**
 * The things an agent can say to the task it is working on, and the one thing it
 * can make.
 *
 * They are tools rather than a convention about what the model writes, because a
 * tool call is the only channel a model has for saying something structured. "I
 * have finished" written in prose is a sentence somebody has to parse and will
 * eventually parse wrongly; `task_done` is a fact.
 *
 * Two of them park the task, and they park it the same way on purpose: a
 * question about how to deliver something and a request for permission are one
 * mechanism seen from two angles. Stop, write down what is being asked, tell
 * whoever should hear about it, and come back with exactly what they said.
 *
 * **`task_draw_picture` is the odd one, and it is here rather than on the agent
 * for one reason: what it produces belongs to this task.** Everything else in
 * this application that draws is a door somebody pressed - the chat's button
 * files its picture on the chat, because that is where a person will look for
 * it. A task has no chat and nobody watching, so the picture is filed against
 * the task and shown with its outcome, and knowing which task that is is
 * something only the loop lending the shed can say. It is offered only to a task
 * that could actually use it: see [TaskPictures.offered].
 */
@Service
class TaskTools(
    private val mapper: ObjectMapper,
    private val pictures: TaskPictures,
) {

    /**
     * The shed for one task's round.
     *
     * It writes nothing about the task. What a park has to record is written by
     * the loop that catches [TaskParked], because the loop is what owns the
     * task's row and its transaction — a tool that wrote its own would be
     * writing from inside a model call that holds none. A drawn picture is the
     * exception and [TaskPictures.draw] says why: it is bytes that have already
     * been paid for, and deferring them to the end of a round that may still
     * throw is losing them.
     */
    fun shed(task: Task): ToolShed = Shed(task)

    private inner class Shed(private val task: Task) : ToolShed {

        /**
         * What this task is offered, which is not always all of them.
         *
         * `AgentTools` states the rule and it holds here: a model is only ever
         * offered tools that will run. An installation with attachments off or
         * a workspace that has chosen no image model has nothing to draw with,
         * and offering the tool anyway spends a turn teaching the model that.
         */
        override fun specs(): List<ToolSpec> =
            if (pictures.offered(task)) SPECS + DRAWING else SPECS

        override fun handles(name: String): Boolean = name in NAMES

        override fun run(call: ToolCall): String = when (call.name) {
            DRAW -> {
                val description = argument(call, "description")?.trim()
                if (description.isNullOrBlank()) {
                    refuse("Say what the picture should be of: task_draw_picture takes a description.")
                } else {
                    when (val drawn = pictures.draw(task, description)) {
                        is Drawing.Refused -> refuse(drawn.reason)
                        /*
                         * The markdown goes back to the model, and the sentence
                         * beside it says it does not have to be used. The
                         * picture is already filed and will be shown under the
                         * outcome whatever the model does next - see
                         * [TaskPictures.outcomeOf] - so this is an offer of
                         * where to *place* it rather than the only way it will
                         * be seen. Handing over a link and depending on the
                         * model to repeat it would be a picture lost every time
                         * one forgot.
                         */
                        is Drawing.Drawn -> mapper.writeValueAsString(
                            mapOf(
                                "drawn" to true,
                                "markdown" to pictures.linkTo(drawn.picture),
                                "note" to "The picture is filed against this task and will be shown with its " +
                                    "outcome. Put the markdown in your task_done summary only if it belongs " +
                                    "at a particular point in it.",
                            ),
                        )
                    }
                }
            }

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
        const val DRAW = "task_draw_picture"

        /*
         * Every name the shed answers to, including one it does not always
         * offer. A model that was offered the drawing tool on an earlier turn -
         * before somebody turned attachments off, say - and calls it now must
         * be told why it will not run, and a name the shed disowns falls through
         * to the agent's own tools and comes back as "there is no tool called
         * task_draw_picture", which is not what happened.
         */
        val NAMES = setOf(DONE, ASK, PERMISSION, DRAW)

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

        /**
         * The fourth, offered only where there is something to draw with.
         *
         * The description spends most of its words on when *not* to call it,
         * because the failure this feature invites is a model that illustrates
         * a report nobody asked to have illustrated - one picture is tens of
         * seconds and real money, and an agent working alone has nobody to stop
         * it. It also says what happens to the picture afterwards: a model that
         * believes the result vanishes unless it repeats the link will repeat
         * it, and the outcome will show the picture twice.
         */
        val DRAWING = ToolSpec(
            name = DRAW,
            description =
                "Draws a picture from a description, using the model this workspace draws with, and keeps it " +
                    "with the task. Call it when a picture is what was asked for - an illustration, a diagram, " +
                    "a mock-up, an image to go with something you are producing - and not to decorate an answer " +
                    "that is prose: each one takes time and costs money. Describe what should be in the picture " +
                    "rather than instructing a model, since the description is sent to a drawing model and not " +
                    "to you. Everything you draw is shown with the task's outcome whether or not you mention it, " +
                    "so use the markdown it hands back only where the picture belongs at a particular point in " +
                    "your summary.",
            parameters = listOf(
                ToolParameterSpec("description", "What the picture should be of.", required = true),
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
