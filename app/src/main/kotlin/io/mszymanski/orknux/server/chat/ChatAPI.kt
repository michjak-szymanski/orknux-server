package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.model.ChatCompletion
import io.mszymanski.orknux.connector.model.ModelService
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.attachment.InstallationSettings
import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional
import java.time.format.DateTimeFormatter

/**
 * The chat screen.
 *
 * A chat belongs to the person who started it — the sidebar says USER CHATS —
 * so every read is filtered by the caller as well as by the workspace. Seeing a
 * workspace does not make somebody's conversations yours to read.
 */
@Controller
class ChatAPI(
    private val chats: ChatService,
    private val models: ModelService,
    private val titles: ChatTitles,
    private val workspaces: WorkspaceRepository,
    private val access: WorkspaceAccess,
    private val agents: AgentRepository,
    private val settings: InstallationSettings,
    private val ownership: ChatOwnership,
) {

    @QueryMapping
    fun chatSessions(@Argument workspaceId: Long): List<ChatSessionView> {
        requireWorkspaceAccess(workspaceId)
        return chats.sessions(workspaceId, currentUser()).map(::describe)
    }

    /**
     * Which of the caller's chats said this, for the search that looks inside
     * them. Off by default on the screen: most searches are for a chat by name,
     * and looking through everything ever said is a different question.
     */
    @QueryMapping
    fun chatsMentioning(@Argument workspaceId: Long, @Argument text: String): List<Long> {
        requireWorkspaceAccess(workspaceId)
        return chats.mentioning(workspaceId, currentUser(), text)
    }

    @QueryMapping
    fun chatSession(@Argument id: Long): ChatSessionView? {
        // Somebody else's chat reads as one that is not there, which is the same
        // answer ChatOwnership gives everywhere else and for the same reason.
        val session = chats.session(id)?.takeIf(ownership::owns) ?: return null
        return describe(session)
    }

    @QueryMapping
    fun chatMessages(@Argument id: Long): List<ChatMessageView> {
        val session = chats.session(id) ?: throw ChatSessionNotFoundException(id)
        requireOwn(session)
        return chats.messages(session).map { ChatMessageView(it.role, it.content, it.actor, it.takes) }
    }

    @MutationMapping
    fun startChat(@Argument input: StartChatInput): ChatSessionView {
        requireChat()
        requireWorkspaceAccess(input.workspaceId)
        return describe(
            chats.start(
                workspaceId = input.workspaceId,
                userId = currentUser(),
                title = input.title ?: "New chat",
                modelId = input.modelId ?: defaultModel(input.workspaceId),
                llmSessionId = input.llmSessionId,
            ),
        )
    }

    /**
     * What a new chat talks to when nobody said.
     *
     * A chat with no model cannot be sent to, so opening one and typing gets an
     * error instead of an answer — for no reason, when the workspace has a model
     * sitting right there. The first active one, in the order the picker shows
     * them, so the choice is at least the obvious one. Still null when the
     * workspace has none, which is a thing to fix by adding a model rather than
     * by guessing.
     */
    private fun defaultModel(workspaceId: Long): Long? =
        models.models(workspaceId).firstOrNull { it.enabled }?.id

    @MutationMapping
    fun renameChat(@Argument id: Long, @Argument title: String): ChatSessionView {
        requireOwn(chats.session(id) ?: throw ChatSessionNotFoundException(id))
        return describe(chats.rename(id, title))
    }

    @MutationMapping
    fun setChatPinned(@Argument id: Long, @Argument pinned: Boolean): ChatSessionView {
        requireOwn(chats.session(id) ?: throw ChatSessionNotFoundException(id))
        return describe(chats.setPinned(id, pinned))
    }

    /** The model selector above the log. Null unsets it, which stops the chat. */
    @MutationMapping
    fun chooseChatModel(@Argument id: Long, @Argument modelId: Long?): ChatSessionView {
        val session = chats.session(id) ?: throw ChatSessionNotFoundException(id)
        requireOwn(session)
        // A model from another workspace is not this chat's to use.
        modelId?.let {
            val model = models.model(it) ?: throw ChatModelUnusableException("That model no longer exists")
            if (model.workspaceId != session.workspaceId) {
                throw ChatModelUnusableException("That model belongs to another workspace")
            }
        }
        return describe(chats.chooseModel(id, modelId))
    }

    /**
     * Hands the chat to an agent, or back to a bare model with null.
     *
     * The access check is the chat's, and which agent may be used is the
     * service's — an agent belongs to a workspace, and this chat belongs to one.
     */
    @MutationMapping
    @Transactional
    fun chooseChatAgent(@Argument id: Long, @Argument agentId: Long?): ChatSessionView {
        val session = chats.session(id) ?: throw ChatSessionNotFoundException(id)
        requireOwn(session)
        return describe(chats.chooseAgent(id, agentId))
    }

    @MutationMapping
    fun deleteChat(@Argument id: Long): Boolean {
        val session = chats.session(id) ?: return false
        requireOwn(session)
        return chats.delete(id)
    }

    /**
     * Sends, and answers. What comes back is the answer, what it cost in time,
     * and what it cost in tokens and money.
     *
     * Composed of the same three calls the streaming path is composed of, and
     * for the reason [ChatService.ask] gives: the model is asked outside any
     * transaction, so the turn does not hold a connection - or, on SQLite, the
     * one write lock there is - for as long as the model and its tools take.
     * What is written is written either side of the asking.
     */
    @MutationMapping
    fun sendChatMessage(@Argument id: Long, @Argument text: String): ChatAnswerView {
        requireChat()
        val session = chats.session(id) ?: throw ChatSessionNotFoundException(id)
        requireOwn(session)

        val start = chats.beginSend(id, text)
        val answer = when (val said = chats.ask(start)) {
            is ChatCompletion.Failed -> throw ChatModelUnusableException(said.reason)
            // The loop runs tools to a conclusion, so nothing reaching here is
            // still asking for one.
            is ChatCompletion.CalledTools ->
                throw ChatModelUnusableException("The model asked for a tool that could not be run")
            is ChatCompletion.Answered -> said
        }
        chats.finishSend(id, answer.content)
        // Same as the streaming path: a first exchange earns the chat a name.
        runCatching { titles.nameFrom(id, text, answer.content) }
        val named = chats.session(id) ?: session
        return ChatAnswerView(
            session = describe(named),
            answer = ChatMessageView("assistant", answer.content),
            millis = answer.millis,
            inputTokens = answer.inputTokens,
            outputTokens = answer.outputTokens,
            cost = models.costOf(start.modelId, answer.inputTokens, answer.outputTokens)?.toDouble(),
        )
    }

    private fun describe(session: ChatSession): ChatSessionView {
        val model = session.modelId?.let { models.model(it) }
        return ChatSessionView(
            id = requireNotNull(session.id),
            workspaceId = session.workspaceId,
            title = session.title,
            pinned = session.pinned,
            modelId = session.modelId,
            modelName = model?.name,
            agentId = session.agentId,
            agentName = session.agentId?.let { agents.findByIdOrNull(it) }?.name,
            llmSessionId = session.llmSessionId,
            createdAt = session.createdAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            lastMessageAt = session.lastMessageAt?.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        )
    }

    /**
     * Refuses while the chat is switched off.
     *
     * Only the writes ask: turning the chat off stops new conversations, it
     * does not make the ones already had unreadable, and an administrator
     * switching it back on should find them where they were.
     */
    private fun requireChat() {
        if (!settings.chatEnabled()) throw ChatDisabledException()
    }

    private fun currentUser(): String =
        SecurityContextHolder.getContext().authentication?.name ?: "system"

    /**
     * A chat is one person's, so seeing the workspace is not enough.
     *
     * Asked of [ChatOwnership] rather than answered here, because the stream and
     * the files sent with a message have to give the same answer to the same
     * question.
     */
    private fun requireOwn(session: ChatSession) = ownership.requireOwn(session)

    private fun requireWorkspaceAccess(workspaceId: Long) {
        access.requireVisible(workspaceId)
    }
}

data class StartChatInput(
    val workspaceId: Long,
    val title: String? = null,
    val modelId: Long? = null,
    /**
     * The LLM session this chat continues, when it was opened from one.
     *
     * A parameter on the ordinary start rather than a mutation of its own:
     * everything else about such a chat — who owns it, what answers it, how it
     * is sent to — is exactly a chat, and a second door would be the same door
     * with one more argument. Null, which is every chat started from the
     * sidebar, is a chat continuing nothing.
     */
    val llmSessionId: Long? = null,
)

data class ChatSessionView(
    val id: Long,
    val workspaceId: Long,
    val title: String,
    val pinned: Boolean,
    val modelId: Long?,
    /** Null when the model it named has been removed. */
    val modelName: String?,
    /** Set when an agent is answering rather than a bare model. */
    val agentId: Long?,
    /** What that agent is called, or null once it has been deleted. */
    val agentName: String?,
    /** The LLM session this chat is continuing, or null for one continuing none. */
    val llmSessionId: Long?,
    val createdAt: String,
    val lastMessageAt: String?,
)

data class ChatMessageView(
    val role: String,
    val content: String,
    /**
     * Who said it, for a turn carried in from the session this chat continues.
     * Null for everything the chat said itself, which is what the screen uses
     * to tell the two apart.
     */
    val actor: String? = null,
    /**
     * What this answer said the earlier times it was given, oldest first. Empty
     * for an answer nobody has asked for again, and for every other kind of
     * line.
     */
    val takes: List<String> = emptyList(),
)

data class ChatAnswerView(
    val session: ChatSessionView,
    val answer: ChatMessageView,
    /** How long the model took, which the screen shows as what it thought for. */
    val millis: Long,
    /**
     * What the provider said it charged for, over the whole turn.
     *
     * The whole turn and not the last call: an agent that looked something up
     * paid for two rounds, and the last one's counts are a fraction of the bill
     * - the same fraction its stopwatch is of [millis], which has been the turn's
     * total since agents could call tools at all.
     *
     * Zero means the provider reported nothing rather than that nothing was
     * spent, so the screen draws nothing rather than a nought.
     */
    val inputTokens: Long,
    val outputTokens: Long,
    /**
     * What those tokens cost at the prices recorded on the model, or null when
     * it carries none. Null rather than zero for the reason `ModelPricing` gives:
     * no price recorded is not a price of nothing.
     */
    val cost: Double?,
)
