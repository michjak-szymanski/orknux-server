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

    /**
     * What one round of the panel produced: the answer, and anything it is
     * offering to change.
     *
     * The suggestion travels beside the answer rather than inside it, because
     * it is not prose: the panel draws it against what the function says now
     * and puts an accept and a reject under it.
     */
    data class Answer(val completion: ChatCompletion, val suggestion: FunctionSuggestion? = null)

    fun answer(
        modelId: Long,
        workspaceId: Long,
        mayWrite: Boolean,
        page: PageContext?,
        said: List<ChatTurn>,
    ): Answer {
        /*
         * Somebody is at a screen here, which is what makes offering a change
         * worth anything - and they may only be offered one where this panel is
         * allowed to change things, since accepting it saves the function.
         */
        val scope = OrknuxScope(workspaceId = workspaceId, mayWrite = mayWrite, watched = mayWrite)
        var offering: FunctionSuggestion? = null
        val offered = orknux.specs(scope)
        val conversation = mutableListOf(ChatTurn(role = "system", content = briefing(page, mayWrite)))
        conversation += said

        var spent = 0L
        var calls = 0
        repeat(MAX_ROUNDS) { round ->
            /*
             * The last round is asked without tools, so it has to answer.
             *
             * A model looking things up one at a time can spend every round on
             * that and never say anything - and what came back was a refusal
             * about the panel rather than an answer, after it had read
             * everything it needed. Taking the tools away on the last round
             * turns "I ran out of looking" into "here is what I found".
             */
            val last = round == MAX_ROUNDS - 1
            when (val answer = models.complete(modelId, conversation, if (last) emptyList() else offered)) {
                is ChatCompletion.Failed -> return Answer(answer)
                is ChatCompletion.Answered -> return Answer(answer.copy(millis = spent + answer.millis), offering)
                is ChatCompletion.CalledTools -> {
                    spent += answer.millis
                    calls += answer.calls.size
                    conversation += answer.turn
                    answer.calls.forEach { call ->
                        log.debug("Quick chat called {}", call.name)
                        /*
                         * Kept as it goes past. The last one wins: a model that
                         * offers two rewrites in one turn has changed its mind,
                         * and showing both would ask somebody to choose between
                         * versions nobody described.
                         */
                        if (call.name == "orknux_suggest_function_code") {
                            orknux.suggestionIn(scope, call.arguments)?.let { offering = it }
                        }
                        conversation += ChatTurn(
                            role = "user",
                            content = orknux.run(scope, call.name, call.arguments),
                            respondingTo = call.id,
                        )
                    }
                }
            }
        }

        /*
         * Only reachable by a model that asked for a tool when it was offered
         * none, which is a provider not honouring the request rather than a
         * conversation that went on too long.
         */
        log.warn("Quick chat asked for tools on its last round after {} calls", calls)
        return Answer(ChatCompletion.Failed("That could not be answered here. Try the Chat page."))
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
                "rather than describing what it might contain. ",
        )
        /*
         * How to offer a change, and what happens to it.
         *
         * Said plainly because the alternative is a model that pastes a whole
         * function into the conversation and asks somebody to copy it - which
         * is what this replaced. The tool puts the change beside what is there
         * now, with an accept and a reject; the next thing in the conversation
         * is which of those they chose, and it is a fact rather than a guess.
         */
        append(
            if (mayWrite) {
                "To change one, call `orknux_suggest_function_code` with the complete new source: they are shown " +
                    "it against what is there now and either accept it or reject it. Do not paste a whole " +
                    "function into your reply and ask them to copy it - offer it with the tool and say in one " +
                    "line what it changes. Nothing is saved unless they accept. " +
                    /*
                     * The sandbox, said up front. A model that does not know it
                     * writes Node - `import crypto` was the first thing one
                     * tried - and then spends three suggestions discovering,
                     * one refusal at a time, what one sentence here prevents.
                     */
                    "Functions run in a locked-down sandbox: no `import` or `require` at all, no Node or browser " +
                    "APIs (no `crypto`, `fs`, `fetch`, `process`), no network. Only plain TypeScript over the " +
                    "declared parameters and standard JavaScript built-ins. If something needs a capability the " +
                    "sandbox lacks, say so instead of trying to smuggle it in. " +
                    /*
                     * One offer at a time, and honest words when it lands. The
                     * transcript this guards against had three suggestions in
                     * flight, a model narrating a wait that was over, and a
                     * placeholder described as the finished algorithm after it
                     * was accepted.
                     */
                    "Offer one change at a time and wait for the outcome - accepted, rejected, or failed - before " +
                    "offering another. When one is accepted, describe what the accepted code actually does, no " +
                    "more; never present a placeholder or a partial version as the finished thing. "
            } else {
                "You cannot change a function here; describe what you would do instead. "
            },
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
        /**
         * How many times the model may be asked before it has to answer.
         *
         * Each tool call it makes costs one. Reading a function is two on its
         * own - find it, then read it - and a model that looks things up one at
         * a time rather than in parallel spends them quickly. The last of these
         * is the one asked without tools.
         */
        const val MAX_ROUNDS = 8
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

        val said = quickChat.answer(modelId, workspaceId, workspace.quickChatMayWrite, asked.page, turns)
        return when (val answer = said.completion) {
            is ChatCompletion.Answered -> ResponseEntity.ok(
                buildMap {
                    put("answer", answer.content)
                    put("millis", answer.millis)
                    // Only when there is one: an absent field is easier for the
                    // panel to read than a null it has to keep testing.
                    said.suggestion?.let {
                        put(
                            "suggestion",
                            mapOf(
                                "functionId" to it.functionId.toString(),
                                "function" to it.name,
                                "note" to it.note,
                                "code" to it.code,
                            ),
                        )
                    }
                },
            )
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
