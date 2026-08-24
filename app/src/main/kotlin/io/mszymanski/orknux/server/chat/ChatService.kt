package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.model.ChatCompletion
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.connector.model.ChatTurn
import io.mszymanski.orknux.connector.model.ModelChatClient
import io.mszymanski.orknux.connector.model.ModelKind
import io.mszymanski.orknux.connector.model.ModelService
import io.mszymanski.orknux.server.llm.LlmSessionRecorder
import io.mszymanski.orknux.server.llm.LlmSessionRepository
import io.mszymanski.orknux.server.llm.RememberedTurn
import io.mszymanski.orknux.server.llm.SessionMemoryBudget
import io.mszymanski.orknux.server.llm.SessionMemoryBudgets
import org.springframework.ai.chat.memory.ChatMemoryRepository
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.MessageType
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.data.repository.findByIdOrNull
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Chats, and the history in them.
 *
 * The history is Spring AI's — [ChatMemoryRepository], keyed by the session's
 * conversation id. Nothing here keeps its own copy of a message, which is what
 * makes this reusable: a workflow run will key a conversation the same way, and
 * every agent in that run will read and append to one thread instead of each
 * carrying its own.
 *
 * Spring AI's store holds a role, some text and an order, and nothing else. What
 * a message was answered by, and how long it took, are this application's
 * business, so they are on the session rather than pretended into the store.
 */
@Service
class ChatService(
    private val sessions: ChatSessionRepository,
    private val history: ChatMemoryRepository,
    private val takes: ChatAnswerTakeRepository,
    private val models: ModelChatClient,
    private val catalogue: ModelService,
    private val agents: AgentRepository,
    private val briefing: AgentBriefing,
    private val conversation: AgentConversation,
    private val entityManager: EntityManager,
    private val llmSessions: LlmSessionRepository,
    private val recorder: LlmSessionRecorder,
    private val budgets: SessionMemoryBudgets,
) {

    /**
     * How much of a session this chat is allowed to carry.
     *
     * The agent's share of the model the chat is actually using, which for a
     * chat is not always the agent's own: the picker at the top of a chat can
     * point it at another model, and the budget has to be a share of the window
     * the request will really be made against.
     *
     * A chat with no agent has no share of its own and falls through to the
     * workspace default, which is the same step an agent that sets nothing
     * takes - the default is the workspace's answer for the sessions held in
     * it, and a chat is one of those. Where the workspace has set none either,
     * it is the built-in allowance, which is what every chat had before.
     */
    private fun budget(chat: ChatSession): SessionMemoryBudget {
        val share = chat.agentId?.let { agents.findByIdOrNull(it)?.memoryShare }
        return budgets.budget(share, chat.workspaceId, chat.modelId)
    }

    fun sessions(workspaceId: Long, userId: String): List<ChatSession> =
        sessions.findByWorkspaceIdAndUserIdOrderByPinnedDescLastMessageAtDescCreatedAtDesc(workspaceId, userId)

    /**
     * The caller's chats in this workspace that said the given thing.
     *
     * Titles are searched on the screen, where every chat is already held; this
     * is the other question — what was actually said — and it is asked of the
     * store rather than by loading every conversation to look through it.
     *
     * Read straight from Spring AI's table because its repository knows one way
     * of finding a message, by conversation. Only the ids come back: the screen
     * has the chats and only needs to know which of them to keep.
     */
    fun mentioning(workspaceId: Long, userId: String, text: String): List<Long> {
        val looking = text.trim()
        if (looking.isEmpty()) return emptyList()

        val held = sessions(workspaceId, userId).associateBy { it.conversationId }
        if (held.isEmpty()) return emptyList()

        @Suppress("UNCHECKED_CAST")
        val found = entityManager
            .createNativeQuery(
                """
                SELECT DISTINCT conversation_id FROM spring_ai_chat_memory
                WHERE conversation_id IN (:conversations) AND content ILIKE :looking ESCAPE '!'
                """.trimIndent(),
            )
            .setParameter("conversations", held.keys)
            // Escaped, so a chat about 100% or a file_name is searched for and
            // not turned into a wildcard by the search itself.
            .setParameter("looking", "%" + looking.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%")
            .resultList as List<String>

        return found.mapNotNull { held[it]?.id }
    }

    fun session(id: Long): ChatSession? = sessions.findByIdOrNull(id)

    /**
     * Everything said in one chat, oldest first, as the store kept it - and for
     * a chat continuing a session, who said the part it did not say itself, and
     * what was looked up in between.
     *
     * The store keeps a role and some text, so a turn carried in from a session
     * comes back indistinguishable from one this chat produced, and the screen
     * signs every one of them with the chat's own model. In a chat opened to
     * work out what an agent did, that is the most misleading line on the page:
     * the agent's own words, under somebody else's name. So the names are put
     * back here, from the session they came out of.
     *
     * And with them the calls, which were never in the thread at all - [seed]
     * copies what was *said*, for a reason written there that has not changed.
     * That reason is about the prompt. This is the reading, and an answer shown
     * with no sign of the lookup that produced it reads as the agent having
     * known something it went and found out. Two audiences, two answers: what
     * goes to the model is [ChatMemoryRepository]'s thread and is untouched by
     * any of this, and what goes on the page is assembled here.
     *
     * Only the stretch that was carried, though. An agent answering *in* this
     * chat records its calls in the same session, and they are deliberately not
     * put back: there is nowhere to put them. The thread keeps a role, some
     * text and an order and no clock at all, so a later call could only be
     * placed by matching words again - and a session is shared by key, so what
     * was written in it after this chat opened may have been written by a run
     * that has nothing to do with this chat. A lookup drawn under an answer
     * that did not make it is a worse lie than the one this fixes, and unlike
     * the carried stretch there is somewhere it is already right: the session's
     * own page, which has every line of the conversation this chat is one end
     * of.
     */
    fun messages(session: ChatSession): List<ChatMessage> {
        /*
         * What each answer said the times it was asked again, put back beside
         * the one that stands. Keyed by place in the thread, which is what the
         * take was written against - so this reads the thread once, with its
         * own index, before anything below reorders or interleaves it.
         */
        val earlier = takes
            .findByChatSessionIdOrderByIdAsc(requireNotNull(session.id))
            .groupBy { it.messageIndex }
        val thread = history.findByConversationId(session.conversationId)
            .mapIndexed { at, message -> ChatMessage(message).copy(takes = earlier[at]?.map { it.content }.orEmpty()) }
        val llmSessionId = session.llmSessionId ?: return thread
        // The same budget the copy was taken under, so the page shows the
        // stretch that was actually carried rather than one merely like it.
        return carried(thread, recorder.readBefore(llmSessionId, session.createdAt, budget(session)))
    }

    /**
     * Puts the session's names back on the turns that came from it, and its
     * calls back between them.
     *
     * Matched rather than counted. Nothing recorded how many turns were seeded,
     * and nothing should have to: [seed] copies a run of the session's lines
     * into the head of the thread, so the run can be found again by looking for
     * it - the longest stretch of the thread's opening that reads word for word
     * like a stretch of the session.
     *
     * Which makes the wrong answer the safe one. A thread that does not line up
     * - because the session was emptied, or because the bounds on what is
     * remembered have since moved - names fewer turns rather than naming them
     * wrongly, and a chat that names none of them is exactly the chat we had
     * before.
     *
     * The matching is against what was said and nothing else. A call was never
     * copied into the thread, so a thread turn is never compared with one;
     * counting one as a turn would put every comparison after it a line out and
     * lose the alignment the paragraph above depends on. The calls are put back
     * afterwards, at the places they were recorded in, and only inside the
     * stretch that did line up - outside it there is nothing to be sure they
     * belong between.
     *
     * @param read the session as it stood when this chat opened, calls and all.
     */
    private fun carried(thread: List<ChatMessage>, read: List<RememberedTurn>): List<ChatMessage> {
        if (thread.isEmpty() || read.isEmpty()) return thread

        val where = read.indices.filter { !read[it].called }
        val said = where.map { read[it] }
        if (said.isEmpty()) return thread

        var found = 0
        var from = 0
        for (start in said.indices.reversed()) {
            var run = 0
            while (
                run < thread.size &&
                start + run < said.size &&
                thread[run].role == said[start + run].role &&
                thread[run].content == said[start + run].content
            ) {
                run++
            }
            if (run > found) {
                found = run
                from = start
            }
        }

        if (found == 0) return thread

        /*
         * Walked over the session rather than over the thread, because the
         * session is the one that has the calls in it. Every line between the
         * two ends that matched is either a turn the thread holds - taken in
         * order, which is what matching them established - or a call, which
         * only the session ever held.
         */
        var turn = 0
        val head = (where[from]..where[from + found - 1]).map { at ->
            val line = read[at]
            if (line.called) {
                ChatMessage(role = RememberedTurn.CALL, content = line.content, actor = line.actor)
            } else {
                thread[turn++].copy(actor = line.actor)
            }
        }
        return head + thread.drop(found)
    }

    /**
     * Opens a chat, optionally continuing an LLM session.
     *
     * @param llmSessionId the session this chat carries on, or null for the
     *   ordinary chat that carries on nothing. What was already said there is
     *   copied into this chat's thread as it opens, which is what makes it a
     *   continuation rather than a blank box — and is why nothing reads the
     *   session again on any later send: the thread already holds it, and
     *   putting it in front of the model twice would have it hear everything
     *   said so far in duplicate.
     */
    @Transactional
    fun start(
        workspaceId: Long,
        userId: String,
        title: String,
        modelId: Long?,
        llmSessionId: Long? = null,
    ): ChatSession {
        val trimmed = title.trim().ifEmpty { throw ChatTitleInvalidException() }
        // Checked before anything is written: a chat bound to a session it may
        // not write into looks like a continuation and is not one.
        llmSessionId?.let { continuable(workspaceId, it) }

        val opened = sessions.save(
            ChatSession(
                workspaceId = workspaceId,
                conversationId = UUID.randomUUID().toString(),
                title = trimmed.take(TITLE_LENGTH),
                userId = userId,
                modelId = modelId ?: defaultModel(workspaceId, userId),
                llmSessionId = llmSessionId,
                createdAt = OffsetDateTime.now(),
            ),
        )
        llmSessionId?.let { seed(opened, it) }
        return opened
    }

    /**
     * Refuses a session this workspace has no business continuing.
     *
     * A session belongs to one workspace, the same way an agent does, and the
     * chat is opened from that workspace's page — so the only way the two
     * disagree is somebody naming an id that is not theirs. Refused by name
     * rather than by silence, because the caller who meant the right session and
     * mistyped deserves to know which half was wrong.
     */
    private fun continuable(workspaceId: Long, llmSessionId: Long) {
        val session = llmSessions.findByIdOrNull(llmSessionId)
            ?: throw ChatLlmSessionUnusableException("That session no longer exists")
        if (session.workspaceId != workspaceId) {
            throw ChatLlmSessionUnusableException("That session belongs to another workspace")
        }
    }

    /**
     * Puts what was already said into the new chat's thread.
     *
     * [LlmSessionRecorder.remembered] rather than the whole transcript: it is
     * already the tail a model can be shown — what was *said*, bounded, oldest
     * first — and the chat wants exactly that. Tool calls and system notes are
     * not turns in a conversation, and a thread holding one would put it in
     * front of the model on every send after this: a call the model never made
     * in this exchange, with no result threaded to it.
     *
     * Which is about the thread, and only the thread. [messages] shows the
     * calls to whoever is reading the chat, off the session and beside the
     * turns rather than in them - the page and the prompt being two different
     * audiences with two different answers.
     */
    private fun seed(chat: ChatSession, llmSessionId: Long) {
        val said = recorder.remembered(llmSessionId, budget(chat))
        if (said.isEmpty()) return
        history.saveAll(
            chat.conversationId,
            said.map { if (it.role == "user") UserMessage(it.content) else AssistantMessage(it.content) },
        )
    }

    /**
     * What a chat answers with when the caller named nothing.
     *
     * A chat with no model is a dead end: every send fails, and the reason is
     * only visible once someone has already typed a message. So a new chat picks
     * up the model this person last chatted with here — a chat opens where the
     * last one left off — and failing that any model the workspace can chat
     * with. Choosing stays a preference rather than a prerequisite.
     *
     * Null only when the workspace has no usable chat model at all, which is a
     * real condition worth reporting rather than papering over.
     */
    private fun defaultModel(workspaceId: Long, userId: String): Long? =
        sessions(workspaceId, userId)
            .sortedByDescending { it.lastMessageAt ?: it.createdAt }
            .firstNotNullOfOrNull { it.modelId }
            ?: catalogue.models(workspaceId)
                .firstOrNull { it.enabled && it.kind == ModelKind.CHAT }
                ?.id

    @Transactional
    fun rename(id: Long, title: String): ChatSession {
        val session = sessions.findByIdOrNull(id) ?: throw ChatSessionNotFoundException(id)
        session.title = title.trim().ifEmpty { throw ChatTitleInvalidException() }.take(TITLE_LENGTH)
        return session
    }

    @Transactional
    fun setPinned(id: Long, pinned: Boolean): ChatSession {
        val session = sessions.findByIdOrNull(id) ?: throw ChatSessionNotFoundException(id)
        session.pinned = pinned
        return session
    }

    @Transactional
    fun chooseModel(id: Long, modelId: Long?): ChatSession {
        val session = sessions.findByIdOrNull(id) ?: throw ChatSessionNotFoundException(id)
        session.modelId = modelId
        // Choosing a bare model ends the agent's part in it: what answers next
        // should be what the picker says answers.
        session.agentId = null
        return session
    }

    /**
     * Hands the chat to one of the workspace's agents, or back to a bare model.
     *
     * The agent's model becomes the chat's, because an agent that cannot be run
     * is not one to hand a conversation to — and a chat answering on some other
     * model would not be answering as what the screen says it is.
     */
    @Transactional
    fun chooseAgent(id: Long, agentId: Long?): ChatSession {
        val session = sessions.findByIdOrNull(id) ?: throw ChatSessionNotFoundException(id)
        if (agentId == null) {
            session.agentId = null
            return session
        }

        val agent = agents.findByIdOrNull(agentId) ?: throw ChatAgentUnusableException("That agent no longer exists")
        if (agent.workspaceId != session.workspaceId) {
            throw ChatAgentUnusableException("That agent belongs to another workspace")
        }
        if (!agent.enabled) throw ChatAgentUnusableException("${agent.name} is not active")
        val model = agent.modelId
            ?: throw ChatAgentUnusableException("${agent.name} has no model chosen, so it cannot answer")

        session.agentId = agentId
        session.modelId = model
        return session
    }

    /**
     * Deletes the chat and the history with it.
     *
     * The store is keyed by conversation, not by us, so it has to be told: a
     * deleted chat that left its messages behind would be a conversation nobody
     * can reach and nobody can clear.
     */
    @Transactional
    fun delete(id: Long): Boolean {
        val session = sessions.findByIdOrNull(id) ?: return false
        history.deleteByConversationId(session.conversationId)
        sessions.delete(session)
        return true
    }

    /**
     * Asks, in whatever way this chat answers, and in no transaction at all.
     *
     * The middle of a send, and the reason a send is three calls rather than
     * one. Asking a model takes as long as the model takes, and an agent's turn
     * is longer still: the loop calls tools, and every tracker tool opens its
     * own transaction because [io.mszymanski.orknux.server.mcp.IssueTools] says
     * why. A transaction held open across all of that is a connection held out
     * of the pool for a minute on Postgres — and on SQLite it is the whole
     * database, which takes one writer at a time. There the turn's transaction
     * held the write lock, the tool's `REQUIRES_NEW` asked for it on a second
     * connection, waited out the busy timeout twice over and failed at commit:
     * every tracker tool an agent called from a chat came back to the model as
     * `Unable to commit against JDBC Connection`, sixty seconds later, whatever
     * the tool had actually answered - including the sentence naming the title
     * limit, which had been composed and was then thrown away by the commit.
     *
     * So nothing here is transactional, and what has to be written is written
     * either side of it: [beginSend] before, [finishSend] after. It is the shape
     * `ChatStreamAPI` was already composed in, said once for both callers.
     */
    fun ask(start: ChatSendStart): ChatCompletion =
        if (start.agentId == null) {
            models.complete(start.modelId, start.turns)
        } else {
            // An agent may need its tools before it can answer; a bare model cannot.
            conversation.answer(start.modelId, start.agentId, start.turns, start.llmSessionId)
        }

    /**
     * The first half of a send: everything settled before the model is asked.
     *
     * Split from [finishSend] because a streaming answer takes as long as the
     * model takes, and holding a database transaction open for a minute holds a
     * connection out of the pool for a minute. The user's turn is still written
     * before the call, for the same reason [send] writes it first.
     */
    @Transactional
    /**
     * @param images pictures sent with this message, as `data:` URLs.
     *
     * Handed to the model as part of the turn rather than described in it: a
     * model that can see is sent the picture, and one that cannot ignores the
     * part. They are not written to the history — that store keeps text, and a
     * base64 image in a conversation log is a conversation log nobody can read.
     */
    fun beginSend(id: Long, text: String, images: List<String> = emptyList()): ChatSendStart {
        val session = sessions.findByIdOrNull(id) ?: throw ChatSessionNotFoundException(id)
        val message = text.trim().ifEmpty { throw ChatMessageEmptyException() }
        val modelId = session.modelId ?: throw ChatModelNotChosenException()

        val thread = history.findByConversationId(session.conversationId)
        history.saveAll(session.conversationId, thread + UserMessage(message))
        session.lastMessageAt = OffsetDateTime.now()
        val into = recording(session)
        recordSaid(session, into, message)

        return ChatSendStart(
            modelId = modelId,
            agentId = session.agentId,
            llmSessionId = into,
            conversationId = session.conversationId,
            // The turn being sent now is built on its own rather than picked out
            // of the thread by its position, because it is no longer the last
            // thing in the list: what the tools returned goes between.
            turns = briefed(session) +
                thread.map { ChatTurn(role(it), it.text.orEmpty()) } +
                recalled(session, into) +
                ChatTurn("user", message, images),
        )
    }

    /**
     * The first half of asking the last answer again.
     *
     * [beginSend] with the question already asked. What the chat said last is
     * taken off the thread and kept as a take, and the model is handed the
     * conversation as it stood the moment before it answered — so it is
     * answering the same question rather than answering itself.
     *
     * Nothing is written into the LLM session on the way in, because nobody
     * said anything: [beginSend] records the person's turn there, and a
     * regenerate has no turn of its own to record. The answer is recorded as
     * usual by [finishSend], which is right — it was said, and a transcript
     * that hides the second attempt is a transcript that cannot explain the
     * session's own shape.
     *
     * **Whatever answers now is whatever the chat says answers now.** That is
     * the model or agent the answer was produced with, unless the picker has
     * been moved since — which is the point of moving it, and the reason
     * "regenerate on a different model" needs no second control.
     *
     * What does not come again is a picture. Images are handed to the model as
     * part of the turn and are deliberately never written to the thread — a
     * base64 image in a conversation log is a log nobody can read — and nothing
     * records which message a file arrived with, so there is nothing here to
     * put back. A regenerate asks the words again.
     */
    @Transactional
    fun beginRegenerate(id: Long): ChatSendStart {
        val session = sessions.findByIdOrNull(id) ?: throw ChatSessionNotFoundException(id)
        val modelId = session.modelId ?: throw ChatModelNotChosenException()

        val thread = history.findByConversationId(session.conversationId)
        val last = thread.lastOrNull()
            ?: throw ChatNothingToRegenerateException("Nothing has been said in this chat yet")
        if (last.messageType != MessageType.ASSISTANT) {
            throw ChatNothingToRegenerateException("The last thing in this chat is not an answer")
        }

        val asked = thread.dropLast(1)
        if (asked.none { it.messageType == MessageType.USER }) {
            throw ChatNothingToRegenerateException("There is no question here to ask again")
        }

        // Kept before it comes off, so a press that turns out to have been a
        // mistake has left the answer somewhere it can be read.
        takes.save(
            ChatAnswerTake(
                chatSessionId = requireNotNull(session.id),
                messageIndex = thread.size - 1,
                content = last.text.orEmpty(),
            ),
        )
        history.saveAll(session.conversationId, asked)

        val into = recording(session)
        return ChatSendStart(
            modelId = modelId,
            agentId = session.agentId,
            llmSessionId = into,
            conversationId = session.conversationId,
            turns = briefed(session) +
                asked.map { ChatTurn(role(it), it.text.orEmpty()) } +
                recalled(session, into),
        )
    }

    /**
     * Puts the answer back where asking again came to nothing.
     *
     * [beginRegenerate] takes the answer off the thread before the model is
     * called, because that is the only way to ask the same question twice. A
     * provider that then refuses would leave the conversation ending on the
     * question, with the answer readable only as a take of a turn that is no
     * longer there — which is a worse outcome than the answer somebody did not
     * like, and one they did not ask for.
     *
     * Found by where it would have sat: the take was written against the index
     * the missing answer had, and that index is now the length of the thread.
     * The newest such take is the one this call took off, and it is deleted as
     * it goes back — nothing was displaced, so there is no earlier take to
     * remember.
     */
    @Transactional
    fun abandonRegenerate(id: Long) {
        val session = sessions.findByIdOrNull(id) ?: return
        val thread = history.findByConversationId(session.conversationId)
        val put = takes.findByChatSessionIdOrderByIdAsc(requireNotNull(session.id))
            .lastOrNull { it.messageIndex == thread.size }
            ?: return
        history.saveAll(session.conversationId, thread + AssistantMessage(put.content))
        takes.delete(put)
    }

    /**
     * The second half: what the model finally said goes into the history.
     *
     * Read back rather than appended to what [beginSend] had, because the model
     * was thinking for a while and the thread is the source of truth for what
     * is in it.
     */
    @Transactional
    fun finishSend(id: Long, answer: String) {
        val session = sessions.findByIdOrNull(id) ?: throw ChatSessionNotFoundException(id)
        history.saveAll(
            session.conversationId,
            history.findByConversationId(session.conversationId) + AssistantMessage(answer),
        )
        session.lastMessageAt = OffsetDateTime.now()
        // Read off the chat rather than opened: [beginSend] has already opened
        // whatever this chat records into, and opening one here would be a
        // session for an answer whose question was never written down.
        recordAnswer(session, session.llmSessionId, answer)
    }

    /**
     * The session this chat records into, opened the first time it needs one.
     *
     * Two ways a chat comes to have one. It was opened from a session's page,
     * in which case it is continuing somebody else's conversation and the
     * pointer was set before it existed. Or it has an agent, in which case it
     * needs one of its own - because an agent calls tools, and what a tool
     * returned has nowhere else to survive the round it was fetched in. Without
     * that, the second question about a lookup is answered out of the model's
     * own account of the first.
     *
     * A chat with a bare model still gets nothing, and that is not an oversight
     * being preserved. It calls no tools, so there is nothing to keep, and the
     * rule this bends - that a chat computes no key, because a key is something
     * two callers can independently arrive at - stays true for every chat that
     * does not need it bent. Where it is bent, it is bent as narrowly as it can
     * be: the key is the conversation id, which is this chat's and nothing
     * else's, so nothing can arrive at it by accident and it is plainly not
     * pretending to be a shared conversation.
     *
     * Opened on the first send rather than when the chat is started, because an
     * agent can be chosen at any point in a chat's life and a chat that never
     * gets one should cost nothing.
     */
    private fun recording(chat: ChatSession): Long? {
        chat.llmSessionId?.let { return it }
        if (chat.agentId == null) return null
        return recorder.open(chat.workspaceId, CHAT_PREFIX, chat.conversationId)
            .also { chat.llmSessionId = it }
    }

    /**
     * What this chat's tools returned lately, to go in front of the model.
     *
     * Between the thread and the question rather than inside the thread. The
     * thread is what was said and is appended to for ever; a result put in it
     * would be paid for on every send from then on, with nothing able to drop
     * the oldest one. Read from the session instead, it is bounded, newest
     * first, and assembled fresh each time - which is what lets a chat carry
     * data it could not afford to carry as history.
     */
    private fun recalled(chat: ChatSession, into: Long?): List<ChatTurn> =
        into?.let { recorder.recalled(it, budget(chat)) }.orEmpty()

    /**
     * What the person said, into the session this chat is continuing.
     *
     * Under their own name rather than the chat's title, because a transcript
     * that cannot say who spoke is not a transcript — and the point of this is
     * that a later run reading the session finds what a person told it, not
     * another line from an agent.
     *
     * Written before the model is asked, for the same reason the user's turn
     * goes into the history first: a provider that fails should leave the
     * session holding what was actually said.
     */
    private fun recordSaid(session: ChatSession, into: Long?, said: String) {
        into?.let { recorder.userSaid(it, session.userId, said) }
    }

    /**
     * And what answered, once the answer is whole.
     *
     * Only for a bare model. An agent's answer is already written by
     * [AgentConversation] as its round ends — tool calls and all, which is a
     * record this could not produce — and writing it again here would put every
     * agent answer in the session twice. A bare model has no such round, so
     * nothing else would ever write the line.
     *
     * Called from the end of a send rather than from the stream, so an answer
     * that arrives in eighty pieces is one line in the transcript rather than
     * eighty.
     */
    private fun recordAnswer(session: ChatSession, into: Long?, answer: String) {
        if (into == null) return
        if (session.agentId != null) return
        val named = session.modelId?.let { catalogue.model(it) }?.name ?: FALLBACK_ACTOR
        recorder.agentSaid(into, named, answer)
    }

    /** Spring AI's types, in the words a provider's API uses. */
    private fun role(message: Message): String = when (message.messageType) {
        MessageType.USER -> "user"
        MessageType.ASSISTANT -> "assistant"
        MessageType.SYSTEM -> "system"
        MessageType.TOOL -> "tool"
    }

    private companion object {
        /** Matches the column. */
        const val TITLE_LENGTH = 200

        /** What a bare model's answer is signed with once the model it named is gone. */
        const val FALLBACK_ACTOR = "model"

        /**
         * What a chat's own session is namespaced under.
         *
         * So the list of sessions says which of them are one chat's private
         * record and which are conversations an agent is having across runs.
         * The two are read very differently and there is nothing else on the
         * row to tell them apart.
         */
        const val CHAT_PREFIX = "chat"
    }

    /**
     * The system turn a chat with an agent opens with.
     *
     * A list rather than a nullable turn so both send paths can prepend it
     * without either having to ask whether there is one — for a chat with a bare
     * model it is simply empty.
     */
    private fun briefed(session: ChatSession): List<ChatTurn> {
        val agent = session.agentId?.let { agents.findByIdOrNull(it) } ?: return emptyList()
        val said = briefing.of(agent) ?: return emptyList()
        return listOf(ChatTurn("system", said))
    }
}

/**
 * One message out of the history.
 *
 * [actor] is who said it in the session this chat is continuing, and is null
 * for everything the chat said itself. Null is therefore also the boundary: the
 * turns that were already there when the chat opened, and the ones said since.
 */
data class ChatMessage(
    val role: String,
    val content: String,
    val actor: String? = null,
    /**
     * What this answer said the earlier times it was given, oldest first, for
     * an answer that has been asked for again. Empty for every other line.
     *
     * The one that stands is [content] and is not in here: this is what a
     * regenerate displaced, kept so that pressing the button is not a way of
     * losing the answer somebody was about to keep.
     */
    val takes: List<String> = emptyList(),
) {
    constructor(message: Message) : this(message.messageType.name.lowercase(), message.text.orEmpty())
}

/**
 * What a send needs before the model is called.
 *
 * [agentId] is set when an agent is answering, because the streaming endpoint
 * has to know whether to run the tool loop and cannot ask the session again
 * without another transaction.
 */
data class ChatSendStart(
    val modelId: Long,
    val conversationId: String,
    val turns: List<ChatTurn>,
    val agentId: Long? = null,
    /**
     * The LLM session this round is recorded into, or null for a chat
     * continuing none. Carried here for the same reason [agentId] is: the
     * streaming endpoint runs outside the transaction that read the chat and
     * cannot ask it again.
     */
    val llmSessionId: Long? = null,
)
