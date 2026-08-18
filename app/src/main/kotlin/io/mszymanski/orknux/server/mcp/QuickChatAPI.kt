package io.mszymanski.orknux.server.mcp

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import io.mszymanski.orknux.connector.model.ChatCompletion
import io.mszymanski.orknux.connector.model.ChatTurn
import io.mszymanski.orknux.connector.model.ModelChatClient
import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.workspace.WorkspaceNotFoundException
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/**
 * The chat that opens beside whatever somebody is looking at.
 *
 * Not the Chat page in miniature: it holds no history, belongs to no workspace
 * conversation and is never written down. What it has instead is where the
 * person is — the page they have open — and orknux's own tools, so "why did
 * last night's sync fail" can be answered by going and looking rather than by
 * asking them to fetch the run themselves.
 *
 * Whether it may do anything, or only look things up, is the workspace's to
 * decide. It reads by default: the panel opens over whatever somebody is
 * reading, and a model that decides "run it" from a question is a worse mistake
 * there than on a page with a button on it.
 */
@Service
class QuickChat(
    private val models: ModelChatClient,
    private val orknux: OrknuxTools,
) {

    fun answer(
        modelId: Long,
        workspaceId: Long,
        mayWrite: Boolean,
        page: PageContext?,
        said: List<ChatTurn>,
    ): ChatCompletion {
        val scope = OrknuxScope(workspaceId = workspaceId, mayWrite = mayWrite)
        val offered = orknux.specs(scope)
        val conversation = mutableListOf(ChatTurn(role = "system", content = briefing(page, mayWrite)))
        conversation += said

        var spent = 0L
        repeat(MAX_ROUNDS) {
            when (val answer = models.complete(modelId, conversation, offered)) {
                is ChatCompletion.Failed -> return answer
                is ChatCompletion.Answered -> return answer.copy(millis = spent + answer.millis)
                is ChatCompletion.CalledTools -> {
                    spent += answer.millis
                    conversation += answer.turn
                    answer.calls.forEach { call ->
                        log.debug("Quick chat called {}", call.name)
                        conversation += ChatTurn(
                            role = "user",
                            content = orknux.run(scope, call.name, call.arguments),
                            respondingTo = call.id,
                        )
                    }
                }
            }
        }

        log.warn("Quick chat was still looking things up after {} rounds", MAX_ROUNDS)
        return ChatCompletion.Failed("That took more looking up than this panel is for. Try the Chat page.")
    }

    /**
     * What the model is told before anything else.
     *
     * The page matters more than it looks. Somebody who opens this while
     * standing on a failed run means *that* run, and a panel that has to ask
     * "which run?" is slower than the page they are already looking at.
     */
    private fun briefing(page: PageContext?, mayWrite: Boolean): String = buildString {
        append(
            "You are the quick assistant inside orknux, a workflow and agent platform. " +
                "Answer in one or two sentences unless asked for more. " +
                "You can look things up with the orknux_ tools; prefer looking to guessing, " +
                "and say plainly when something is not there. ",
        )
        /*
         * Told to use the links the tools give it.
         *
         * Every run, workflow and agent comes back with a `url`, and an answer
         * that names one without linking to it leaves somebody to go and find
         * it — which is the whole thing this panel was meant to save.
         */
        append(
            "Whenever you mention a run, a workflow, an agent or a function, link to it using the `url` " +
                "the tool gave you, as a markdown link like [run 20](url). Never invent a link. ",
        )
        /*
         * The code, and what may be done with it.
         *
         * Reading a function before discussing it is the difference between
         * helping with the code and describing what a function of that name
         * might contain. Saying that a suggestion cannot be saved is the honest
         * half: nothing here writes a function, because what runs is compiled
         * from TypeScript by the editor in the browser and there is no compiler
         * on this side to keep the two halves the same.
         */
        append(
            "When a question is about a function's code, read it with `orknux_function` before answering " +
                "rather than describing what it might contain. You can suggest a rewrite in your reply, " +
                "as code; you cannot save one, so say that it has to be pasted into the editor. ",
        )
        /*
         * Said as well as enforced. The scope already withholds the tool, so a
         * model told nothing would offer to start a workflow and then fail —
         * and where it may, being told saves it from refusing out of caution.
         */
        append(
            if (mayWrite) {
                "You may also change things when asked to — starting or repeating a run, turning a workflow " +
                    "or an agent on or off. These are real: say what you did. You cannot delete anything."
            } else {
                "You cannot change anything: you may look, and nothing else."
            },
        )
        if (page != null) {
            append("\n\nThe person is looking at ")
            append(page.label?.takeIf { it.isNotBlank() } ?: "a page")
            page.path?.takeIf { it.isNotBlank() }?.let { append(" ($it)") }
            append(
                ". Take \"this\", \"here\" and \"it\" to mean whatever that page is showing, " +
                    "and use the ids in the path when they help.",
            )
        }
    }

    private companion object {
        const val MAX_ROUNDS = 6
        val log = LoggerFactory.getLogger(QuickChat::class.java)
    }
}

/**
 * Answers the panel.
 *
 * REST rather than GraphQL to sit beside the other two things a chat needs —
 * transcription and speech — and because what goes up is a short conversation
 * that is never stored, which is a poor fit for a mutation that implies it was.
 */
@RestController
class QuickChatAPI(
    private val workspaces: WorkspaceRepository,
    private val quickChat: QuickChat,
    private val access: WorkspaceAccess,
) {

    @PostMapping("/api/workspaces/{workspaceId}/quick-chat")
    fun ask(
        @PathVariable workspaceId: Long,
        @RequestBody asked: QuickChatRequest,
    ): ResponseEntity<Any> {
        val workspace = workspaces.findByIdOrNull(workspaceId) ?: throw WorkspaceNotFoundException(workspaceId)
        access.requireVisible(workspace)

        val modelId = workspace.quickChatModelId
            ?: return refuse(HttpStatus.CONFLICT, "This workspace has no quick chat model.")

        val turns = asked.messages
            .filter { it.content.isNotBlank() }
            .map { ChatTurn(role = if (it.role == "assistant") "assistant" else "user", content = it.content) }
        if (turns.isEmpty()) return refuse(HttpStatus.BAD_REQUEST, "There is nothing to answer.")

        return when (
            val answer = quickChat.answer(modelId, workspaceId, workspace.quickChatMayWrite, asked.page, turns)
        ) {
            is ChatCompletion.Answered -> ResponseEntity.ok(mapOf("answer" to answer.content, "millis" to answer.millis))
            is ChatCompletion.Failed -> refuse(HttpStatus.BAD_GATEWAY, answer.reason)
            // The loop above ends on one of the two above; a tool call reaching
            // here would mean it did not, and saying so beats answering blank.
            is ChatCompletion.CalledTools -> refuse(HttpStatus.BAD_GATEWAY, "The model asked for a tool and stopped.")
        }
    }

    private fun refuse(status: HttpStatus, says: String): ResponseEntity<Any> =
        ResponseEntity.status(status).body(mapOf("error" to says))
}

/** Where the person is, as the interface knows it. */
data class PageContext @JsonCreator constructor(
    @JsonProperty("label") val label: String?,
    @JsonProperty("path") val path: String?,
)

data class QuickChatMessage @JsonCreator constructor(
    @JsonProperty("role") val role: String,
    @JsonProperty("content") val content: String,
)

data class QuickChatRequest @JsonCreator constructor(
    @JsonProperty("messages") val messages: List<QuickChatMessage>,
    @JsonProperty("page") val page: PageContext?,
)
