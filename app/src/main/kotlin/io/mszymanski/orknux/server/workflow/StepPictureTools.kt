package io.mszymanski.orknux.server.workflow

import io.mszymanski.orknux.connector.model.ToolCall
import io.mszymanski.orknux.connector.model.ToolParameterSpec
import io.mszymanski.orknux.connector.model.ToolSpec
import io.mszymanski.orknux.server.chat.ToolShed
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper

/**
 * The one thing an agent inside a run can make: a picture, from a description
 * it writes itself.
 *
 * Issue #349. A run could already draw - an image node does - but only from a
 * prompt somebody wrote on the node before the run started, so a graph that
 * should illustrate *whatever the agent found* had to guess the subject in
 * advance and draw whether or not there was anything worth drawing. This is the
 * same drawing with the decision moved to where it is actually made.
 *
 * ### Why it is `draw_picture` and not a grant on the agent
 *
 * `ChatTools` argues at length that a bare `draw_picture` says nothing about
 * where the picture goes, and names its own `chat_draw_picture` for it. The
 * same argument puts this in a shed rather than in [AgentTools]: filing a
 * picture needs a run and a step to file it against, and only the thing running
 * the loop knows which those are. An agent granted this on its row would carry
 * it into a chat, where the run it needs does not exist.
 *
 * The name stays plain because from inside a run there is exactly one place a
 * picture can go and the description says so. Its twin in a task is
 * `task_draw_picture` and in a chat `chat_draw_picture`; an agent is never
 * offered two of them at once.
 *
 * Offered only where it will work: see [StepPictures.offered]. An installation
 * with attachments off or a workspace that has chosen no image model has
 * nothing to draw with, and telling the model otherwise spends a turn teaching
 * it that.
 */
@Service
class StepPictureTools(
    private val mapper: ObjectMapper,
    private val pictures: StepPictures,
) {

    /**
     * The shed for one step of one run, or null where there is nothing to draw
     * with.
     *
     * Null rather than a shed that refuses everything, so the caller can hand
     * [io.mszymanski.orknux.server.chat.AgentConversation] nothing at all and
     * the round is exactly the round it was before this existed.
     */
    fun shed(executionId: Long, nodeKey: String, workspaceId: Long): ToolShed? =
        if (pictures.offered(workspaceId)) Shed(executionId, nodeKey, workspaceId) else null

    private inner class Shed(
        private val executionId: Long,
        private val nodeKey: String,
        private val workspaceId: Long,
    ) : ToolShed {

        override fun specs(): List<ToolSpec> = listOf(DRAWING)

        override fun handles(name: String): Boolean = name == DRAW

        override fun run(call: ToolCall): String {
            if (call.name != DRAW) return refuse("There is no tool called ${call.name}")

            val description = argument(call, "description")?.trim()
            if (description.isNullOrBlank()) {
                return refuse("Say what the picture should be of: $DRAW takes a description.")
            }

            return when (val drawn = pictures.draw(executionId, nodeKey, workspaceId, description)) {
                is StepDrawing.Refused -> refuse(drawn.reason)

                /*
                 * The markdown goes back, and the sentence beside it says it
                 * does not have to be used. The picture is filed against this
                 * step and the run graph draws it under this node whatever the
                 * model does next, so this is an offer of where to *place* it
                 * rather than the only way it will be seen. Handing over a link
                 * and depending on the model to repeat it would be a picture
                 * lost every time one forgot.
                 */
                is StepDrawing.Drawn -> mapper.writeValueAsString(
                    mapOf(
                        "drawn" to true,
                        "url" to "${StepPictures.DOWNLOAD_PATH}/${requireNotNull(drawn.picture.id)}",
                        "markdown" to pictures.linkTo(drawn.picture),
                        "note" to "The picture is filed against this run and is shown under this node. Put the " +
                            "markdown in your answer only if it belongs at a particular point in it.",
                    ),
                )
            }
        }

        private fun argument(call: ToolCall, name: String): String? = runCatching {
            mapper.readTree(call.arguments).path(name).takeIf { it.isTextual }?.stringValue()
        }.getOrNull()

        /**
         * A refusal the model reads, as JSON like every other answer.
         *
         * Not an exception: a tool that could not do the thing is a fact the
         * conversation carries on from, and the sentence is what lets the agent
         * decide whether to rephrase, draw something else, or say plainly that
         * it could not.
         */
        private fun refuse(reason: String): String =
            mapper.writeValueAsString(mapOf("drawn" to false, "reason" to reason))
    }

    private companion object {
        const val DRAW = "draw_picture"

        val DRAWING = ToolSpec(
            name = DRAW,
            description = "Draw a picture from a description. The picture is filed with this run and shown " +
                "under this step; you are handed markdown for it in case you want it at a particular point " +
                "in your answer.",
            parameters = listOf(
                ToolParameterSpec(
                    name = "description",
                    description = "What the picture should be of, in your own words. The more it says about " +
                        "subject, composition and style, the closer the picture is to what you meant.",
                    required = true,
                ),
            ),
        )
    }
}
