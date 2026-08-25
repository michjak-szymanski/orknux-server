package io.mszymanski.orknux.server.chat

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import io.mszymanski.orknux.connector.model.ChatCompletion
import io.mszymanski.orknux.connector.model.ModelChatClient
import io.mszymanski.orknux.connector.model.ModelService
import io.mszymanski.orknux.server.attachment.ChatAttachments
import io.mszymanski.orknux.server.attachment.InstallationSettings
import io.mszymanski.orknux.server.stream.ServerSentEvents
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
    private val mapper: ObjectMapper,
) {

    /**
     * Says something, and streams the answer back.
     *
     * Three events are sent: `chunk` for each piece as it lands, `done` with
     * what the turn took and what it cost, and `error` when it could not answer.
     * The whole answer is written to the history when the stream ends, so a chat
     * reloaded afterwards reads exactly as it did live.
     *
     * Access is checked before anything is written, because after the first byte
     * the status code has already been sent and there is no way to say no.
     */
    @PostMapping("/api/chats/{id}/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun stream(@PathVariable id: Long, @RequestBody request: ChatStreamRequest): StreamingResponseBody {
        if (!settings.chatEnabled()) throw ChatDisabledException()
        val session = chats.session(id) ?: throw ChatSessionNotFoundException(id)
        requireOwn(session)

        // Tied to the chat here, and read for anything the model can look at.
        val sent = attachments.attach(session, request.attachmentIds)
        return answering(id, chats.beginSend(id, request.text, attachments.imagesOf(sent)), request.text)
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
    fun regenerate(@PathVariable id: Long): StreamingResponseBody {
        if (!settings.chatEnabled()) throw ChatDisabledException()
        val session = chats.session(id) ?: throw ChatSessionNotFoundException(id)
        requireOwn(session)

        return answering(id, chats.beginRegenerate(id), said = null) { chats.abandonRegenerate(id) }
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
     */
    private fun answering(
        id: Long,
        start: ChatSendStart,
        said: String?,
        giveUp: () -> Unit = {},
    ): StreamingResponseBody {
        return StreamingResponseBody { out ->
            /*
             * The frames themselves are [ServerSentEvents]'. They used to be
             * written here by hand, and the task page's stream would have been a
             * second copy of the same four lines - which is one more place for
             * the flush to be left out, and leaving it out does not break
             * anything visibly: the answer simply arrives all at once at the end,
             * which looks like a slow model.
             */
            val stream = ServerSentEvents(out, mapper)
            fun send(event: String, payload: Any) = stream.send(event, payload)

            // Set the moment the answer is safely in the history, so the
            // rescue below cannot run on top of one that did arrive.
            var kept = false

            try {
                /*
                 * An agent answers through the tool loop, which cannot stream:
                 * a round that asks for a lookup produces no text worth showing,
                 * and what to say is only settled once the loop ends. So its
                 * answer arrives as one chunk. The screen is the same either
                 * way; what differs is that an agent thinks before it types.
                 */
                val answer = if (start.agentId == null) {
                    client.stream(start.modelId, start.turns) { piece -> send("chunk", mapOf("text" to piece)) }
                } else {
                    chats.ask(start).also { whole ->
                        if (whole is ChatCompletion.Answered) send("chunk", mapOf("text" to whole.content))
                    }
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
                        chats.finishSend(id, answer.content)
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

    private fun requireOwn(session: ChatSession) = ownership.requireOwn(session)

    private companion object {
        val log = LoggerFactory.getLogger(ChatStreamAPI::class.java)
    }
}
