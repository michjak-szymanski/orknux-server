package io.mszymanski.orknux.server.chat

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import io.mszymanski.orknux.connector.model.ChatCompletion
import io.mszymanski.orknux.connector.model.Hangup
import io.mszymanski.orknux.connector.model.ModelChatClient
import io.mszymanski.orknux.connector.model.ModelService
import io.mszymanski.orknux.server.attachment.ChatAttachments
import io.mszymanski.orknux.server.attachment.InstallationSettings
import io.mszymanski.orknux.server.stream.ReaderWatch
import io.mszymanski.orknux.server.stream.ServerSentEvents
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody
import tools.jackson.databind.ObjectMapper

/**
 * What the browser sends to say something.
 *
 * The creator is bound explicitly for the same reason `LoginRequest` binds its
 * own: Boot 4 ships Jackson 3, which has no Kotlin module on the classpath, so
 * constructor parameter names are not enough to deserialize from.
 */
data class ChatStreamRequest @JsonCreator constructor(
    @JsonProperty("text") val text: String,
    /**
     * What was attached to this message, by id.
     *
     * Sent with the message rather than linked afterwards, because the model is
     * called during the send: a picture that arrives after the answer is a
     * picture the answer could not have seen.
     */
    @JsonProperty("attachmentIds") val attachmentIds: List<Long> = emptyList(),
)

/**
 * The one part of the chat that is not GraphQL.
 *
 * A model composes an answer over seconds — a large local one over minutes —
 * and a single mutation can only return when it has finished, which is a blank
 * screen for the whole of that time. This sends the answer as it arrives.
 *
 * Server-sent events over a POST rather than a GraphQL subscription: the browser
 * client here is `fetch`, not a GraphQL client, so a subscription would mean
 * adding a websocket transport and the `graphql-ws` protocol to send one string.
 * Everything else about a chat — starting one, listing them, reading the
 * history — stays where it was.
 */
@RestController
class ChatStreamAPI(
    private val chats: ChatService,
    private val client: ModelChatClient,
    /** Only to cost an answer: the prices are the model's. */
    private val models: ModelService,
    private val titles: ChatTitles,
    private val ownership: ChatOwnership,
    private val attachments: ChatAttachments,
    private val settings: InstallationSettings,
    /** How this endpoint finds out that the person who asked has walked away. */
    private val readers: ReaderWatch,
    private val mapper: ObjectMapper,
    private val chatTools: ChatTools,
) {

    /**
     * Says something, and streams the answer back.
     *
     * Six events are sent: `chunk` for each piece of the answer as it lands,
     * `thinking` for each piece of a reasoning model's thinking, `call` for a
     * lookup the moment an agent makes one, `called` for what that lookup gave
     * back, `done` with what the turn took and what it cost, and `error` when it
     * could not answer. The whole answer is written to the history when the
     * stream ends, so a chat reloaded afterwards reads exactly as it did live.
     *
     * Access is checked before anything is written, because after the first byte
     * the status code has already been sent and there is no way to say no.
     */
    @PostMapping("/api/chats/{id}/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun stream(
        @PathVariable id: Long,
        @RequestBody request: ChatStreamRequest,
        response: HttpServletResponse,
    ): StreamingResponseBody {
        if (!settings.chatEnabled()) throw ChatDisabledException()
        val session = chats.session(id) ?: throw ChatSessionNotFoundException(id)
        requireOwn(session)

        // Tied to the chat here, and read for anything the model can look at.
        val sent = attachments.attach(session, request.attachmentIds)
        return answering(
            id,
            chats.beginSend(id, request.text, attachments.imagesOf(sent)),
            request.text,
            response,
            chatTools.shed(session),
        )
    }

    /**
     * Asks the last answer again, and streams the new one back the same way.
     *
     * A door of its own rather than a flag on [stream], because nothing is
     * being said: there is no text, nothing is attached, and the turn that goes
     * to the model is the conversation with its own last answer taken off.
     * Everything after that is identical, which is why both end in [answering].
     *
     * The chat is not renamed off a regenerate. A name is taken from the first
     * exchange, and this is not one - it is the same exchange, answered again,
     * and letting the second attempt rename a chat somebody has already found
     * in the sidebar would move it out from under them.
     */
    @PostMapping("/api/chats/{id}/regenerate", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun regenerate(@PathVariable id: Long, response: HttpServletResponse): StreamingResponseBody {
        if (!settings.chatEnabled()) throw ChatDisabledException()
        val session = chats.session(id) ?: throw ChatSessionNotFoundException(id)
        requireOwn(session)

        val shed = chatTools.shed(session)
        return answering(id, chats.beginRegenerate(id), said = null, response = response, shed = shed) {
            chats.abandonRegenerate(id)
        }
    }

    /**
     * The asking itself, once what to ask has been settled.
     *
     * @param said what the person typed, for naming a chat that has no name
     *   yet, or null where nothing was said - a regenerate.
     * @param giveUp what to undo where no answer arrives. A regenerate has
     *   already taken the old answer off the thread by this point, so a
     *   provider that refuses would otherwise leave the chat ending on the
     *   question - the one outcome worse than the answer somebody did not like.
     * @param shed the chat's own tools, lent to the agent for this round. The
     *   same shed the blocking door lends: what an agent may do must not depend
     *   on which of the two the browser used.
     */
    private fun answering(
        id: Long,
        start: ChatSendStart,
        said: String?,
        response: HttpServletResponse,
        shed: ToolShed,
        giveUp: () -> Unit = {},
    ): StreamingResponseBody {
        // Nothing between here and the browser may hold a piece of the answer
        // back. See the task stream, which sets these for the same reason.
        response.setHeader("Cache-Control", "no-cache, no-transform")
        response.setHeader("X-Accel-Buffering", "no")

        return StreamingResponseBody { _ ->
            /*
             * The frames themselves are [ServerSentEvents]', which is also what
             * put the pieces on the wire.
             *
             * They used to be written here by hand, as `out.write` and
             * `out.flush` - and that flush did nothing. Spring hands a
             * `StreamingResponseBody` a stream whose `flush` is a no-op, so this
             * answer moved when the container's buffer filled at eight kilobytes
             * rather than when the model produced a piece of it. It was
             * invisible because a model writing prose does eventually fill eight
             * kilobytes: a long answer appeared to stream, in lurches, and a
             * short one arrived whole at the end and read as a slow model.
             */
            val stream = ServerSentEvents(response, mapper)
            fun send(event: String, payload: Any) = stream.send(event, payload)

            // Set the moment the answer is safely in the history, so the
            // rescue below cannot run on top of one that did arrive.
            var kept = false

            /*
             * The handle the model call is stopped by when nobody is left to
             * read it.
             *
             * Pulled from [ReaderWatch]'s thread rather than this one, which is
             * inside the call and will not come out of it on its own - that is
             * the whole of why it is a handle rather than a flag. This thread
             * comes back some moments later and asks the same object what
             * happened, because what it is then holding is the wreckage of an
             * answer nobody wanted rather than an answer.
             */
            val hangup = Hangup()

            try {
                /*
                 * An agent's answer still arrives as one chunk, and its working
                 * does not.
                 *
                 * The tool loop cannot stream text: a round that asks for a
                 * lookup produces no answer worth showing, and what to say is
                 * only settled once the loop ends. What it *can* report is what
                 * it is doing - which lookup it just made, what came back, and
                 * what it thought on the way - and those are the things
                 * somebody watching a minute of silence wanted. So the answer
                 * lands whole and the working lands as it happens.
                 *
                 * A bare model calls no tools; what it can have is thinking,
                 * and that streams beside the answer.
                 */
                /*
                 * Watched while it runs, because the container says nothing
                 * about a browser that has gone until something is written to
                 * it - and between the question and the first piece of the
                 * answer there is nothing to write. See [ReaderWatch] and issue
                 * #299: interrupting used to stop the listening and nothing
                 * else, so the model went on writing an answer nobody would
                 * ever read and it went on being charged for.
                 */
                val answer = readers.whileReading(stream, gone = { hangup.hangUp() }) {
                    if (start.agentId == null) {
                        client.stream(
                            start.modelId,
                            start.turns,
                            onThinking = { piece -> send("thinking", mapOf("text" to piece)) },
                            hangup = hangup,
                        ) { piece -> send("chunk", mapOf("text" to piece)) }
                    } else {
                        chats.ask(start, watching { event, payload -> send(event, payload) }, shed, hangup)
                            .also { whole ->
                                if (whole is ChatCompletion.Answered) send("chunk", mapOf("text" to whole.content))
                            }
                    }
                }

                /*
                 * Given up on, so nothing is made of what came back.
                 *
                 * Not written to the history in particular. What the model had
                 * produced when the reader went is part of an answer that was
                 * stopped on purpose, and a chat reopened tomorrow ending in
                 * half a sentence attributed to the model is a worse record
                 * than one ending on the question. A regenerate is put back the
                 * way it is for anything else that did not answer, or the chat
                 * would be left ending on a question it had already answered
                 * once.
                 */
                if (hangup.hungUp) {
                    log.debug("Chat {} was given up on while it was being answered", id)
                    if (!kept) runCatching(giveUp).onFailure { log.warn("Chat {} could not be put back", id, it) }
                    return@StreamingResponseBody
                }

                when (answer) {
                    is ChatCompletion.Failed -> {
                        giveUp()
                        send("error", mapOf("reason" to answer.reason))
                    }
                    // The loop runs tools to a conclusion, so nothing here is
                    // still asking for one.
                    is ChatCompletion.CalledTools -> {
                        giveUp()
                        send("error", mapOf("reason" to "The model asked for a tool that could not be run"))
                    }
                    is ChatCompletion.Answered -> {
                        chats.finishSend(
                            id,
                            answer.content,
                            answer.reasoning,
                            answer.reasoningMillis,
                            answer.inputTokens,
                            answer.outputTokens,
                        )
                        kept = true
                        // Naming it is not part of the answer, so a companion
                        // model that will not answer costs the chat nothing.
                        if (said != null) {
                            runCatching { titles.nameFrom(id, said, answer.content) }
                                .onFailure { log.warn("Could not name chat {}", id, it) }
                        }
                        /*
                         * What the turn took and what it cost, in one frame.
                         *
                         * Costed here rather than on the screen because the
                         * prices are the model's and the model is the server's
                         * - a browser working it out would need them sent, and
                         * then two places would round money. Null where the
                         * model carries no prices, which the screen shows as
                         * nothing rather than as nought.
                         */
                        /*
                         * The chat's own running total is deliberately not on
                         * this frame. It is on `ChatSession`, which the screen
                         * re-reads at the end of every turn anyway, and one
                         * number arriving by two roads is one number that can
                         * disagree with itself.
                         */
                        send(
                            "done",
                            mapOf(
                                "millis" to answer.millis,
                                "inputTokens" to answer.inputTokens,
                                "outputTokens" to answer.outputTokens,
                                "cost" to models.costOf(start.modelId, answer.inputTokens, answer.outputTokens),
                            ),
                        )
                    }
                }
            } catch (closed: Exception) {
                // The reader went away, or the write failed. Nothing to report
                // to: the only thing left is not to lose it in silence.
                log.warn("Chat {} stream ended early", id, closed)
                if (!kept) runCatching(giveUp).onFailure { log.warn("Chat {} could not be put back", id, it) }
            }
        }
    }

    /**
     * The agent's round, turned into frames for whoever is reading.
     *
     * A thin adapter and deliberately nothing more: [RoundWatch] is told these
     * things beside the [io.mszymanski.orknux.server.llm.LlmSessionRecorder]
     * calls that keep them, so nothing here decides what is recorded and
     * nothing here can lose a record by failing. What it does decide is the
     * vocabulary, which is the chat's own — a task's stream says `step` about
     * the same facts, because a task page is following a durable log and this
     * is following one answer being composed.
     *
     * `call` carries `at`, which is where the call came in the round. `called`
     * carries the same `at` and is how the browser finds the line to fill in.
     * See [RoundWatch] for why it is a counter rather than the provider's own
     * call id or the session line's.
     */
    private fun watching(send: (String, Any) -> Unit) = object : RoundWatch {
        override fun thinking(text: String) = send("thinking", mapOf("text" to text))

        override fun called(at: Int, tool: String, arguments: String) =
            send("call", mapOf("at" to at, "tool" to tool, "arguments" to arguments))

        override fun returned(at: Int, result: String, failed: Boolean) =
            send("called", mapOf("at" to at, "result" to result, "failed" to failed))
    }

    private fun requireOwn(session: ChatSession) = ownership.requireOwn(session)

    private companion object {
        val log = LoggerFactory.getLogger(ChatStreamAPI::class.java)
    }
}
