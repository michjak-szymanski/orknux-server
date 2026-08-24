package io.mszymanski.orknux.server.integration

import com.slack.api.bolt.request.RequestHeaders
import com.slack.api.bolt.request.builtin.EventRequest
import com.slack.api.bolt.util.EventsApiPayloadParser
import com.slack.api.model.event.MessageEvent
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.connection.ConnectionType
import io.mszymanski.orknux.connector.connection.IncomingAction
import io.mszymanski.orknux.connector.connection.IncomingEvent
import io.mszymanski.orknux.connector.connection.SlackBotUsers
import io.mszymanski.orknux.connector.connection.SlackClients
import io.mszymanski.orknux.connector.connection.SlackListener
import io.mszymanski.orknux.connector.connection.SlackProperties
import io.mszymanski.orknux.connector.connection.WorkspaceConnection
import io.mszymanski.orknux.connector.connection.WorkspaceConnectionRepository
import io.mszymanski.orknux.connector.proxy.ProxyRouter
import io.mszymanski.orknux.connector.proxy.ProxyRuleSource
import io.mszymanski.orknux.server.security.plainCredentials
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.context.ApplicationEventPublisher
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * What a Slack message becomes on its way to a trigger.
 *
 * **`parent_user_id` is the thing being pinned here.** The whole of a reply
 * trigger rests on it: Slack puts the author of a thread's parent message on
 * every reply, and a bot token is a Slack user, so "a reply to something we
 * wrote" is that id against our own. If the SDK did not surface it there would
 * be no feature, so the first test takes a payload in the shape Slack sends and
 * runs it through the SDK's own parser — `EventRequest` and
 * `EventsApiPayloadParser`, which is exactly what Bolt hands a handler — rather
 * than constructing a `MessageEvent` by hand and proving only that Kotlin can
 * set a field.
 *
 * The rest is the listener itself, called the way a socket calls it. Nothing
 * here opens one and nothing reaches the network: `auth.test` is answered by a
 * stand-in on the loopback address, the way every other Slack test here answers
 * it.
 */
class SlackMessageEventTest {

    private lateinit var api: HttpServer

    /** Keyed by method name and answered verbatim. */
    private val answers = ConcurrentHashMap<String, String>()

    /** What the listener published, in order. */
    private val raised = CopyOnWriteArrayList<IncomingEvent>()

    @BeforeEach
    fun start() {
        answers.clear()
        raised.clear()
        answers["auth.test"] = ourselves(OUR_BOT)

        // The SDK works out which class a `message` payload is by looking up a
        // table that registering a handler fills in. `App.event(MessageEvent)`
        // is what fills it in the application; here nothing has opened an app,
        // so the same registration is made by hand and the parse below is the
        // one Bolt would do.
        EventsApiPayloadParser.getEventTypeAndSubtype(MessageEvent::class.java)

        api = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        api.createContext("/") { exchange ->
            respond(exchange, answers[exchange.requestURI.path.substringAfterLast('/')] ?: """{"ok":true}""")
        }
        api.start()
    }

    @AfterEach
    fun stop() = api.stop(0)

    /**
     * The one that had to be checked before any of this was worth building.
     *
     * The body is a `message` event as Slack sends one over Socket Mode, and it
     * is parsed by the SDK rather than by the test. `parentUserId` coming out of
     * the far end holding `parent_user_id` is what the reply trigger is built
     * on.
     */
    @Test
    fun `a thread reply arrives carrying the parent's author`() {
        val request = EventRequest(threadReplyPayload(), RequestHeaders(emptyMap()))

        val message = EventsApiPayloadParser.buildEventPayload<MessageEvent>(request).event

        assertThat(message.parentUserId).isEqualTo(OUR_BOT)
        assertThat(message.threadTs).isEqualTo("1700000000.000100")
        assertThat(message.ts).isEqualTo("1700000000.000200")
        assertThat(message.user).isEqualTo("U0000ALICE")
        assertThat(message.channel).isEqualTo("C0000000001")
    }

    /** And a message that is not in a thread has neither, which is how they are told apart. */
    @Test
    fun `a message outside a thread carries no parent and no thread`() {
        val request = EventRequest(plainMessagePayload(), RequestHeaders(emptyMap()))

        val message = EventsApiPayloadParser.buildEventPayload<MessageEvent>(request).event

        assertThat(message.parentUserId).isNull()
        assertThat(message.threadTs).isNull()
    }

    @Test
    fun `a plain message is one event, and says where it came from`() {
        listener().receive(CONNECTION_ID, WORKSPACE_ID, parsed(plainMessagePayload()), "T00000001")

        val published = await1()
        assertThat(published.map { it.action }).containsExactly(IncomingAction.MESSAGE)
        assertThat(published.single().text).isEqualTo("the build is red again")
        assertThat(published.single().context)
            .containsEntry("channel", "C0000000001")
            .containsEntry("user", "U0000ALICE")
            .containsEntry("ts", "1700000000.000300")
            // No thread, so a reply to this one starts a thread on the message.
            .containsEntry("threadTs", "1700000000.000300")
            .containsEntry("channelType", "channel")
            .containsEntry("slackWorkspaceId", "T00000001")
            .doesNotContainKey("parentUserId")
    }

    /**
     * Two events from one message, on purpose.
     *
     * A reply is a message, and a definition waiting on messages in a channel
     * should not stop hearing them because somebody used a thread. Which of the
     * two a trigger wants is the trigger's business.
     */
    @Test
    fun `a thread reply is both a message and a reply, and carries the parent through`() {
        listener().receive(CONNECTION_ID, WORKSPACE_ID, parsed(threadReplyPayload()), "T00000001")

        val published = await(2)
        assertThat(published.map { it.action })
            .containsExactlyInAnyOrder(IncomingAction.MESSAGE, IncomingAction.REPLY)
        assertThat(published.map { it.context["parentUserId"] }).containsOnly(OUR_BOT)
        // Where an answer goes: into the thread, not beside it.
        assertThat(published.map { it.context["threadTs"] }).containsOnly("1700000000.000100")
    }

    /**
     * The loop guard, which is not optional.
     *
     * A workflow that answers in a thread it watches sees its own answer come
     * back as a reply to a message one of our bots wrote — which is precisely
     * what a reply trigger looks for — and would start itself again, and again.
     */
    @Test
    fun `a message from a bot raises nothing at all`() {
        listener().receive(CONNECTION_ID, WORKSPACE_ID, parsed(botReplyPayload()), "T00000001")

        assertThat(nothingArrives()).isEmpty()
    }

    /** And the other half of it: our own bot user, where Slack sent no `bot_id`. */
    @Test
    fun `a message from this connection's own bot user raises nothing`() {
        listener().receive(CONNECTION_ID, WORKSPACE_ID, parsed(ourOwnMessagePayload()), "T00000001")

        assertThat(nothingArrives()).isEmpty()
    }

    /** [SlackListener] as the application builds it, with Slack on the loopback address. */
    private fun listener(): SlackListener {
        val clients = SlackClients(ProxyRouter(ProxyRuleSource { emptyList() }))
        clients.webApi.config.methodsEndpointUrlPrefix = "http://${api.address.hostString}:${api.address.port}/api/"
        val repository = connections()
        return SlackListener(
            repository,
            ApplicationEventPublisher { published -> raised += published as IncomingEvent },
            SlackProperties(),
            plainCredentials(),
            clients,
            SlackBotUsers(repository, plainCredentials(), clients),
        )
    }

    private fun connections(): WorkspaceConnectionRepository {
        val connection = WorkspaceConnection(
            id = CONNECTION_ID,
            workspaceId = WORKSPACE_ID,
            name = "Support Slack",
            type = ConnectionType.SLACK,
            url = "https://slack.com/api",
            secret = "xoxb-not-a-real-token",
        )
        val repository = mock(WorkspaceConnectionRepository::class.java)
        `when`(repository.findById(CONNECTION_ID)).thenReturn(Optional.of(connection))
        return repository
    }

    /** The SDK's own parse, so the test never sets a field the wire has to fill. */
    private fun parsed(body: String): MessageEvent =
        EventsApiPayloadParser.buildEventPayload<MessageEvent>(EventRequest(body, RequestHeaders(emptyMap()))).event

    /** Publishing is handed to a virtual thread, so what arrives is waited for. */
    private fun await(count: Int): List<IncomingEvent> {
        await().atMost(FIVE_SECONDS).untilAsserted { assertThat(raised).hasSize(count) }
        return raised.toList()
    }

    private fun await1(): List<IncomingEvent> = await(1)

    /**
     * Nothing, proved by waiting rather than by looking straight away — a
     * dropped event and an event still on its way look identical for a moment.
     */
    private fun nothingArrives(): List<IncomingEvent> {
        Thread.sleep(SETTLE.toMillis())
        return raised.toList()
    }

    private fun ourselves(userId: String) =
        """{"ok":true,"team":"Acme","team_id":"T00000001","user":"orknux","user_id":"$userId","bot_id":"B0000000001"}"""

    /** A reply in a thread our bot started, in the shape Slack sends it. */
    private fun threadReplyPayload() = envelope(
        """
        {
          "type": "message",
          "channel": "C0000000001",
          "user": "U0000ALICE",
          "text": "on it",
          "ts": "1700000000.000200",
          "thread_ts": "1700000000.000100",
          "parent_user_id": "$OUR_BOT",
          "event_ts": "1700000000.000200",
          "channel_type": "channel"
        }
        """,
    )

    private fun plainMessagePayload() = envelope(
        """
        {
          "type": "message",
          "channel": "C0000000001",
          "user": "U0000ALICE",
          "text": "the build is red again",
          "ts": "1700000000.000300",
          "event_ts": "1700000000.000300",
          "channel_type": "channel"
        }
        """,
    )

    /** Somebody else's bot, answering in the same thread. */
    private fun botReplyPayload() = envelope(
        """
        {
          "type": "message",
          "channel": "C0000000001",
          "user": "U0000OTHER",
          "bot_id": "B0000000009",
          "text": "acknowledged",
          "ts": "1700000000.000400",
          "thread_ts": "1700000000.000100",
          "parent_user_id": "$OUR_BOT",
          "event_ts": "1700000000.000400",
          "channel_type": "channel"
        }
        """,
    )

    /** Our own bot, with no `bot_id` on it: the half `bot_id` alone would miss. */
    private fun ourOwnMessagePayload() = envelope(
        """
        {
          "type": "message",
          "channel": "C0000000001",
          "user": "$OUR_BOT",
          "text": "deploying now",
          "ts": "1700000000.000500",
          "thread_ts": "1700000000.000100",
          "parent_user_id": "$OUR_BOT",
          "event_ts": "1700000000.000500",
          "channel_type": "channel"
        }
        """,
    )

    private fun envelope(event: String) = """
        {
          "token": "not-a-real-token",
          "team_id": "T00000001",
          "api_app_id": "A00000001",
          "type": "event_callback",
          "event_id": "Ev00000001",
          "event_time": 1700000000,
          "event": $event
        }
    """.trimIndent()

    private fun respond(exchange: HttpExchange, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
        exchange.close()
    }

    private companion object {
        const val CONNECTION_ID = 7L
        const val WORKSPACE_ID = 3L

        /** The Slack user the connection's own bot token posts as. */
        const val OUR_BOT = "U0000ORKNU"

        val FIVE_SECONDS: Duration = Duration.ofSeconds(5)

        /** Long enough for a virtual thread to have run, short enough to be a test. */
        val SETTLE: Duration = Duration.ofMillis(750)
    }
}
