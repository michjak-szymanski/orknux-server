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
 * ### The grant says whether, the shed says where
 *
 * Whether an agent may draw is a grant in its Tools list, ticked by name like
 * every other tool, because that list is where somebody looks to see what an
 * agent may do. Where the picture goes is not on the agent
 * and cannot be: filing needs a run and a step to file against, and only the
 * thing running the loop knows which those are. An agent that carried the tool
 * itself would carry it into a chat, where the run it needs does not exist.
 *
 * The name stays plain because from inside a run there is exactly one place a
 * picture can go and the description says so. Its twin in a task is
 * `task_draw_picture` and in a chat `chat_draw_picture`; an agent is never
 * offered two of them at once.
 *
 * Offered only where it will work: the grant, plus [StepPictures.offered]. An
 * installation with attachments off or a workspace that has chosen no image
 * model has nothing to draw with, and telling the model otherwise spends a turn
 * teaching it that.
 */
@Service
class StepPictureTools(
    private val mapper: ObjectMapper,
    private val pictures: StepPictures,
    /**
     * Where a drawn picture's bytes go so that something else can send them.
     *
     * The session's own store, which is what every tool that produces bytes
     * already writes to and what every tool that uploads them reads from.
     */
    private val scratch: io.mszymanski.orknux.workflow.script.SessionScratch,
) {

    private val log = org.slf4j.LoggerFactory.getLogger(javaClass)

    /**
     * The shed for one step of one run, or null where there is nothing to draw
     * with.
     *
     * Three things have to be true and they are asked in one place: the agent
     * was granted pictures, the installation keeps attachments, and the
     * workspace has chosen a model that draws. Null rather than a shed that
     * refuses everything, so the caller hands
     * [io.mszymanski.orknux.server.chat.AgentConversation] nothing at all and
     * the round is exactly the round it was before this existed - which is
     * also the rule `AgentTools` states: a model is only ever offered tools
     * that will run.
     *
     * @param granted the agent's own switch, from its form beside Shells.
     */
    fun shed(
        executionId: Long,
        nodeKey: String,
        workspaceId: Long,
        granted: Boolean = true,
        /**
         * The AI session this round is recorded in, or null for a node that
         * keeps none.
         *
         * What it buys is the only thing that makes a drawn picture
         * *deliverable*: the bytes go into that session's store under a key,
         * and a key is the currency every tool that uploads bytes takes. With
         * no session there is nowhere to put one, and the answer says so by
         * not carrying one - the same thing the plugins do.
         */
        sessionId: Long? = null,
    ): ToolShed? =
        if (granted && pictures.offered(workspaceId)) {
            Shed(executionId, nodeKey, workspaceId, sessionId)
        } else {
            null
        }

    private inner class Shed(
        private val executionId: Long,
        private val nodeKey: String,
        private val workspaceId: Long,
        private val sessionId: Long?,
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
                 * A key first, because a key is the thing that can be
                 * *delivered*.
                 *
                 * The model used to be handed a link and a sentence inviting
                 * it to paste the markdown. It has no other way to put a
                 * picture anywhere, so it pasted - and a chat client with no
                 * document to resolve the address against printed the
                 * construction instead of a picture. The picture was filed
                 * under a node in an interface the reader of that chat never
                 * sees, so the delivery failed and the consolation prize was a
                 * link nobody could follow.
                 *
                 * So the bytes go into the session's store under a key, which
                 * is what every tool that uploads a file already takes - a
                 * content key rather than the bytes themselves. Which tools
                 * those are is not this one's business: they arrive with
                 * whatever plugins an installation has loaded, and core naming
                 * one of them would be core knowing a plugin by name. The markdown stays, because the
                 * run's own interface reads it - and the note says which of
                 * the two is a delivery and which is not.
                 */
                is StepDrawing.Drawn -> {
                    val key = keyFor(drawn)
                    mapper.writeValueAsString(
                        buildMap {
                            put("drawn", true)
                            if (key != null) put("key", key)
                            put("note", noteFor(key))
                        },
                    )
                }
            }
        }

        /**
         * The bytes, where they can be reached by name, or null where they
         * cannot.
         *
         * Null for a node with no session - there is no store to put them in -
         * and null where the store refused them, which it does above its own
         * size. Both are said rather than hidden: an answer with no key is a
         * picture the model cannot deliver, and it needs to know that before
         * it promises somebody a screenshot.
         */
        private fun keyFor(drawn: StepDrawing.Drawn): String? {
            val session = sessionId ?: return null
            val key = "picture." + requireNotNull(drawn.picture.id)

            // A JSON-encoded *string*: the sandbox does `JSON.parse` on what it
            // reads, and the upload doors then require what comes out to be a
            // string of base64.
            val refused = scratch.put(session, key, mapper.writeValueAsString(drawn.base64))
            if (refused != null) {
                log.info("A drawn picture was not put in session {}'s store: {}", session, refused)
                return null
            }
            return key
        }

        /**
         * What to say about a picture that can be handed over, and one that
         * cannot.
         *
         * No markdown in either. It was in the answer so the run's own
         * interface had a line to draw, and a model handed a link and no other
         * way to deliver anything pasted the link - into a chat that has no
         * document to resolve an address against, which printed the
         * construction. The interface draws the picture from the row; the
         * model gets the one thing it can actually act on.
         */
        private fun noteFor(key: String?): String = if (key != null) {
            "The picture is drawn and its bytes are in this session's store under `key`. To put " +
                "the picture in front of somebody, pass that key to whichever of your tools sends " +
                "or uploads a file - one that takes a content key rather than the bytes " +
                "themselves. `key` is not a URL: writing it into a link or an image gives an " +
                "address that resolves to nothing. It is also filed against this run and shown " +
                "under this node, so it is seen without being mentioned."
        } else {
            "The picture is drawn and filed against this run, and is shown under this node. Its " +
                "bytes could not be left anywhere this session can reach, so nothing here can " +
                "upload it - say where it is rather than promising to send it."
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

    companion object {
        /**
         * The name it is granted and called by.
         *
         * A row in the agent's Tools list like any other, rather than a switch
         * of its own: that list is where somebody looks to see what an agent
         * may do, and a capability that is not in it is one nobody finds.
         */
        const val DRAW = "draw_picture"

        val DRAWING = ToolSpec(
            name = DRAW,
            /*
             * What it answers, said exactly.
             *
             * This used to promise markdown, and went on promising it after
             * the answer stopped carrying any: the tool now hands back a store
             * key and nothing else. A model told to place markdown, and given
             * one string, wrote the string into an image link - `![a
             * tower](picture.18)` - which is not an address, resolves to
             * nothing, and drew "This picture is gone" where the picture was
             * meant to be. A description that describes a different tool is
             * worse than none.
             */
            description = "Draw a picture from a description. The picture is filed with this run and is " +
                "shown under this step whether or not you mention it, so you never have to place it. " +
                "What comes back is `key`, which is a handle for a tool that sends or uploads a file - " +
                "not an address. Never put it in a link or an image: it resolves to nothing.",
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
