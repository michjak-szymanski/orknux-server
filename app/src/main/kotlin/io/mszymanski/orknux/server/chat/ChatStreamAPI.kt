package io.mszymanski.orknux.server.chat

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import io.mszymanski.orknux.connector.model.ChatCompletion
import io.mszymanski.orknux.connector.model.ModelChatClient
import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.workspace.WorkspaceNotFoundException
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import io.mszymanski.orknux.server.attachment.ChatAttachments
import io.mszymanski.orknux.server.attachment.InstallationSettings
import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.MediaType
import org.springframework.security.core.context.SecurityContextHolder
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
    private val titles: ChatTitles,
    private val conversation: AgentConversation,
    private val workspaces: WorkspaceRepository,
    private val access: WorkspaceAccess,
    private val attachments: ChatAttachments,
    private val settings: InstallationSettings,
    private val mapper: ObjectMapper,
) {

    /**
     * Says something, and streams the answer back.
     *
     * Three events are sent: `chunk` for each piece as it lands, `done` with how
     * long the model took, and `error` when it could not answer. The whole
     * answer is written to the history when the stream ends, so a chat reloaded
     * afterwards reads exactly as it did live.
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
        val start = chats.beginSend(id, request.text, attachments.imagesOf(sent))

        return StreamingResponseBody { out ->
            fun send(event: String, payload: Any) {
                // One frame: the event name, the JSON, and the blank line that
                // ends it. Flushed each time, or the answer arrives all at once
                // anyway and the whole exercise is pointless.
                out.write("event: $event\ndata: ${mapper.writeValueAsString(payload)}\n\n".toByteArray())
                out.flush()
            }

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
                    conversation.answer(start.modelId, start.agentId, start.turns).also { whole ->
                        if (whole is ChatCompletion.Answered) send("chunk", mapOf("text" to whole.content))
                    }
                }
                when (answer) {
                    is ChatCompletion.Failed -> send("error", mapOf("reason" to answer.reason))
                    // The loop runs tools to a conclusion, so nothing here is
                    // still asking for one.
                    is ChatCompletion.CalledTools ->
                        send("error", mapOf("reason" to "The model asked for a tool that could not be run"))
                    is ChatCompletion.Answered -> {
                        chats.finishSend(id, answer.content)
                        // Naming it is not part of the answer, so a companion
                        // model that will not answer costs the chat nothing.
                        runCatching { titles.nameFrom(id, request.text, answer.content) }
                            .onFailure { log.warn("Could not name chat {}", id, it) }
                        send("done", mapOf("millis" to answer.millis))
                    }
                }
            } catch (closed: Exception) {
                // The reader went away, or the write failed. Nothing to report
                // to: the only thing left is not to lose it in silence.
                log.warn("Chat {} stream ended early", id, closed)
            }
        }
    }

    private fun requireOwn(session: ChatSession) {
        val workspace = workspaces.findByIdOrNull(session.workspaceId)
            ?: throw WorkspaceNotFoundException(session.workspaceId)
        access.requireVisible(workspace)
        val user = SecurityContextHolder.getContext().authentication?.name ?: "system"
        if (session.userId != user) throw ChatSessionNotFoundException(requireNotNull(session.id))
    }

    private companion object {
        val log = LoggerFactory.getLogger(ChatStreamAPI::class.java)
    }
}
