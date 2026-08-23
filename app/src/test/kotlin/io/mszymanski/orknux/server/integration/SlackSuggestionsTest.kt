package io.mszymanski.orknux.server.integration

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.connection.ConnectionType
import io.mszymanski.orknux.connector.connection.SlackClients
import io.mszymanski.orknux.connector.connection.SlackDirectory
import io.mszymanski.orknux.connector.connection.SlackTargetKind
import io.mszymanski.orknux.connector.connection.SlackTargetOutcome
import io.mszymanski.orknux.connector.connection.WorkspaceConnection
import io.mszymanski.orknux.connector.connection.WorkspaceConnectionRepository
import io.mszymanski.orknux.connector.proxy.ProxyRouter
import io.mszymanski.orknux.connector.proxy.ProxyRuleSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * What a Slack connection is willing to offer for somebody to pick, which is the
 * other half of [SlackDirectoryTest]'s question.
 *
 * Two things are being held here, and neither of them is the filtering. The
 * first is that no conclusion is ever drawn from a list that stopped short: a
 * rate limit part way through a read must never come back as a channel that
 * does not exist, because the name is very likely in the part that was never
 * read. The second is that the list is read once - Slack rate-limits per
 * connection, so a read on every keystroke would take the workflow that posts
 * messages down with it.
 *
 * Slack is a stand-in on the loopback address, reached the way
 * [SlackDirectoryTest] reaches one: the SDK's endpoint prefix is pointed at it,
 * so the real client, the real paging and the real parsing all run and nothing
 * goes near the network.
 */
class SlackSuggestionsTest {

    private lateinit var api: HttpServer

    /**
     * Keyed by method name. Each answer is used once and the last one repeats,
     * so a page that always names a next cursor is one entry rather than ten.
     */
    private val answers = ConcurrentHashMap<String, MutableList<String>>()

    /** Which methods were called, in order. */
    private val asked = CopyOnWriteArrayList<String>()

    /** Every `Authorization` header the stand-in saw, to prove what leaks and what does not. */
    private val authorizations = CopyOnWriteArrayList<String>()

    @BeforeEach
    fun start() {
        answers.clear()
        asked.clear()
        authorizations.clear()
        // The SDK resolves the team behind a token before it calls anything, so
        // this answers whether a test asked for it or not.
        answering("auth.test", """{"ok":true,"team":"Acme","team_id":"T00000001"}""")

        api = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        api.createContext("/") { exchange ->
            val method = exchange.requestURI.path.substringAfterLast('/')
            asked += method
            exchange.requestHeaders.getFirst("Authorization")?.let { authorizations += it }
            respond(exchange, next(method))
        }
        api.start()
    }

    @AfterEach
    fun stop() = api.stop(0)

    @Test
    fun `channels come back filtered by what has been typed, likeliest first`() {
        answering(
            "conversations.list",
            channels(
                "C0000000001" to "notifications",
                "C0000000002" to "general",
                "C0000000003" to "notes",
            ),
        )

        val suggestions = directory().suggest(CONNECTION_ID, SlackTargetKind.CHANNEL, "not")

        assertThat(suggestions.outcome).isEqualTo(SlackTargetOutcome.FOUND)
        // Both start with what was typed and neither is exact, so the shorter
        // name sorts first; #general is not offered at all.
        assertThat(suggestions.matches.map { it.name }).containsExactly("#notes", "#notifications")
        assertThat(suggestions.matches.map { it.id }).containsExactly("C0000000003", "C0000000001")
        // Everything that matches is here, so there is nothing to say about it.
        assertThat(suggestions.complete).isTrue()
        assertThat(suggestions.message).isEmpty()
    }

    @Test
    fun `what was typed exactly comes before what merely contains it`() {
        answering("conversations.list", channels("C0000000001" to "release-notes", "C0000000002" to "notes"))

        val suggestions = directory().suggest(CONNECTION_ID, SlackTargetKind.CHANNEL, "#notes")

        assertThat(suggestions.matches.map { it.name }).containsExactly("#notes", "#release-notes")
    }

    @Test
    fun `an empty field asks for the first few of everything`() {
        answering("conversations.list", channels("C0000000001" to "general", "C0000000002" to "notes"))

        val suggestions = directory().suggest(CONNECTION_ID, SlackTargetKind.CHANNEL, "")

        // What a picker shows when it opens, which is why an empty field is a
        // question and not a refusal.
        assertThat(suggestions.matches.map { it.name }).containsExactly("#general", "#notes")
        assertThat(suggestions.outcome).isEqualTo(SlackTargetOutcome.FOUND)
    }

    @Test
    fun `a member comes back with the handle to type and the name to recognise`() {
        answering(
            "users.list",
            """{"ok":true,"members":[""" +
                """{"id":"U0000000001","name":"alice","real_name":"Alice Adams",""" +
                """"profile":{"display_name":"","real_name":"Alice Adams"}},""" +
                """{"id":"U0000000002","name":"alastair","deleted":true},""" +
                """{"id":"U0000000003","name":"alarms"}""" +
                """]}""",
        )

        val suggestions = directory().suggest(CONNECTION_ID, SlackTargetKind.USER, "@al")

        // The handle is what the field is filled with, so it is what a person
        // could have typed; the real name is the second line that tells them
        // which handle they are looking at.
        assertThat(suggestions.matches.map { it.name }).containsExactly("@alarms", "@alice")
        assertThat(suggestions.matches.single { it.name == "@alice" }.realName).isEqualTo("Alice Adams")
        // Nothing to say twice: a member whose only name is their handle gets
        // one line rather than the same line drawn under itself.
        assertThat(suggestions.matches.single { it.name == "@alarms" }.realName).isNull()
        // A member who has left is not somebody to suggest sending to.
        assertThat(suggestions.matches.map { it.id }).doesNotContain("U0000000002")
    }

    @Test
    fun `a member is also found by the name they are known by`() {
        answering(
            "users.list",
            """{"ok":true,"members":[{"id":"U0000000001","name":"aa7","real_name":"Alice Adams"}]}""",
        )

        val suggestions = directory().suggest(CONNECTION_ID, SlackTargetKind.USER, "adams")

        assertThat(suggestions.matches.map { it.id }).containsExactly("U0000000001")
    }

    @Test
    fun `a token that cannot read channels suggests nothing, and says why in a line`() {
        answering("conversations.list", """{"ok":false,"error":"missing_scope","needed":"channels:read"}""")

        val suggestions = directory().suggest(CONNECTION_ID, SlackTargetKind.CHANNEL, "not")

        // The distinction the whole feature exists for, in the picker as well as
        // beside the field: a box that empties itself with nothing said under it
        // reads as a broken connection.
        assertThat(suggestions.outcome).isEqualTo(SlackTargetOutcome.UNCHECKED)
        assertThat(suggestions.outcome).isNotEqualTo(SlackTargetOutcome.NOT_FOUND)
        assertThat(suggestions.matches).isEmpty()
        assertThat(suggestions.complete).isFalse()
        // Word for word what the check beside it would have said.
        assertThat(suggestions.message)
            .startsWith("Not checked")
            .contains("channels:read and groups:read")
        assertThat(suggestions.message.length).isLessThan(120)
    }

    @Test
    fun `the same sentence names the scope a member list needs`() {
        answering("users.list", """{"ok":false,"error":"missing_scope","needed":"users:read"}""")

        val suggestions = directory().suggest(CONNECTION_ID, SlackTargetKind.USER, "al")

        assertThat(suggestions.outcome).isEqualTo(SlackTargetOutcome.UNCHECKED)
        assertThat(suggestions.message).startsWith("Not checked").contains("users:read")
        assertThat(suggestions.message.length).isLessThan(120)
    }

    @Test
    fun `a name nothing matches is advice, and says what a complete list still does not hold`() {
        answering("conversations.list", channels("C0000000001" to "general"))

        val suggestions = directory().suggest(CONNECTION_ID, SlackTargetKind.CHANNEL, "#notifcations")

        assertThat(suggestions.outcome).isEqualTo(SlackTargetOutcome.NOT_FOUND)
        assertThat(suggestions.message)
            .contains("#notifcations")
            .contains("a private one this bot is not in looks the same")
        assertThat(suggestions.message.length).isLessThan(120)
    }

    @Test
    fun `every page is read`() {
        answering(
            "conversations.list",
            channels("C0000000001" to "notes", cursor = "second-page"),
            channels("C0000000002" to "notifications"),
        )

        val suggestions = directory().suggest(CONNECTION_ID, SlackTargetKind.CHANNEL, "not")

        // The one lookup the check does would have stopped at the first page and
        // reported the rest unchecked. This is what the cache buys.
        assertThat(suggestions.matches.map { it.name }).containsExactly("#notes", "#notifications")
        assertThat(suggestions.complete).isTrue()
        assertThat(asked.count { it == "conversations.list" }).isEqualTo(2)
    }

    @Test
    fun `a Slack larger than one read gets through says so rather than pretending`() {
        // A page that always names another page: more members than the cap on a
        // single read allows for.
        answering("users.list", members("U0000000001" to "alice", cursor = "another-page"))

        val suggestions = directory().suggest(CONNECTION_ID, SlackTargetKind.USER, "al")

        assertThat(suggestions.outcome).isEqualTo(SlackTargetOutcome.FOUND)
        assertThat(suggestions.matches.map { it.name }).containsExactly("@alice")
        // Honest about being a part of it, which is the whole difference between
        // a short list and a wrong one.
        assertThat(suggestions.complete).isFalse()
        assertThat(suggestions.message).contains("members here than one lookup reads").contains("keep typing")
        assertThat(suggestions.message.length).isLessThan(120)
        // The cap is a cap: the read ends rather than paging through a Slack of
        // any size while somebody waits.
        assertThat(asked.count { it == "users.list" }).isEqualTo(MOST_PAGES)
    }

    @Test
    fun `a list already read is not read again`() {
        answering("conversations.list", channels("C0000000001" to "notifications"))
        val directory = directory()

        directory.suggest(CONNECTION_ID, SlackTargetKind.CHANNEL, "n")
        directory.suggest(CONNECTION_ID, SlackTargetKind.CHANNEL, "no")
        val third = directory.suggest(CONNECTION_ID, SlackTargetKind.CHANNEL, "not")

        // Three keystrokes, one read. Slack's rate limit belongs to the
        // connection and not to this feature, so a read per keystroke would be
        // paid for by whatever the workflow was posting.
        assertThat(asked.count { it == "conversations.list" }).isEqualTo(1)
        assertThat(third.matches.map { it.name }).containsExactly("#notifications")
    }

    @Test
    fun `the two lists are cached apart`() {
        answering("conversations.list", channels("C0000000001" to "alerts"))
        answering("users.list", members("U0000000001" to "alice"))
        val directory = directory()

        assertThat(directory.suggest(CONNECTION_ID, SlackTargetKind.CHANNEL, "al").matches.map { it.name })
            .containsExactly("#alerts")
        // The target decides which list is read, and one being cached is not the
        // other being cached.
        assertThat(directory.suggest(CONNECTION_ID, SlackTargetKind.USER, "al").matches.map { it.name })
            .containsExactly("@alice")
        assertThat(asked).contains("conversations.list", "users.list")
    }

    @Test
    fun `a rate limit part way through is a partial list and never a missing channel`() {
        answering(
            "conversations.list",
            channels("C0000000001" to "notifications", cursor = "second-page"),
            """{"ok":false,"error":"ratelimited"}""",
        )
        val directory = directory()

        // What was read is still worth offering, and it is marked as a part.
        val matched = directory.suggest(CONNECTION_ID, SlackTargetKind.CHANNEL, "not")
        assertThat(matched.outcome).isEqualTo(SlackTargetOutcome.FOUND)
        assertThat(matched.matches.map { it.name }).containsExactly("#notifications")
        assertThat(matched.complete).isFalse()
        assertThat(matched.message).contains("rate-limiting")
        assertThat(matched.message.length).isLessThan(120)

        // And this is the one that matters. The channel may well be on the page
        // Slack refused to hand over, so saying it does not exist would send
        // somebody off to correct a name that was right.
        val unmatched = directory.suggest(CONNECTION_ID, SlackTargetKind.CHANNEL, "#somewhere-else")
        assertThat(unmatched.outcome).isEqualTo(SlackTargetOutcome.UNCHECKED)
        assertThat(unmatched.outcome).isNotEqualTo(SlackTargetOutcome.NOT_FOUND)
        assertThat(unmatched.matches).isEmpty()
        assertThat(unmatched.message).startsWith("Not checked").contains("rate-limiting")
        assertThat(unmatched.message.length).isLessThan(120)

        // A rate-limited Slack is not asked again on the next keystroke either.
        assertThat(asked.count { it == "conversations.list" }).isEqualTo(2)
    }

    @Test
    fun `a connection with nothing to ask with suggests nothing instead of calling Slack`() {
        val none = directory(secret = null).suggest(CONNECTION_ID, SlackTargetKind.CHANNEL, "not")
        assertThat(none.outcome).isEqualTo(SlackTargetOutcome.UNCHECKED)
        assertThat(none.message).contains("no bot token stored")

        val appToken = directory(secret = "xapp-1-test").suggest(CONNECTION_ID, SlackTargetKind.USER, "al")
        assertThat(appToken.outcome).isEqualTo(SlackTargetOutcome.UNCHECKED)
        assertThat(appToken.message).contains("xoxb- bot token, not the app-level one")

        val http = directory(type = ConnectionType.HTTP).suggest(CONNECTION_ID, SlackTargetKind.CHANNEL, "any")
        assertThat(http.outcome).isEqualTo(SlackTargetOutcome.UNCHECKED)
        assertThat(http.message).contains("Not a Slack connection")

        assertThat(asked).isEmpty()
    }

    @Test
    fun `nothing that comes back carries the token`() {
        answering("conversations.list", channels("C0000000001" to "notifications"))
        answering("users.list", """{"ok":false,"error":"invalid_auth"}""")
        val directory = directory(secret = SECRET)

        val found = directory.suggest(CONNECTION_ID, SlackTargetKind.CHANNEL, "not")
        val refused = directory.suggest(CONNECTION_ID, SlackTargetKind.USER, "al")

        val said = listOf(found.message, refused.message) +
            found.matches.flatMap { listOfNotNull(it.id, it.name, it.realName) }
        assertThat(said).noneMatch { it.contains(SECRET) || it.contains("xoxb") }
        // And the token did go out, so this is not passing because nothing ran.
        assertThat(authorizations).isNotEmpty().allMatch { it == "Bearer $SECRET" }
    }

    @Test
    fun `one list holds both when no kind was asked for, and every row says which it is`() {
        answering("conversations.list", channels("C0000000001" to "alerts"))
        answering("users.list", members("U0000000001" to "alastair"))

        val suggestions = directory().suggest(CONNECTION_ID, null, "al")

        assertThat(suggestions.outcome).isEqualTo(SlackTargetOutcome.FOUND)
        assertThat(suggestions.matches.map { it.name }).containsExactly("#alerts", "@alastair")
        // A row in a merged list that cannot say which kind it is can neither be
        // drawn nor acted on, so the kind travels on the row.
        assertThat(suggestions.matches.map { it.kind })
            .containsExactly(SlackTargetKind.CHANNEL, SlackTargetKind.USER)
        assertThat(suggestions.complete).isTrue()
        assertThat(suggestions.message).isEmpty()
    }

    @Test
    fun `a channel comes before a member that matches just as well`() {
        answering("conversations.list", channels("C0000000001" to "support"))
        answering("users.list", members("U0000000001" to "support"))

        val suggestions = directory().suggest(CONNECTION_ID, null, "support")

        // Both are exact, so the tie-break decides. Channels first: a channel
        // name means one thing in a Slack and a display name need not, so the
        // row read first is the unambiguous one.
        assertThat(suggestions.matches.map { it.name }).containsExactly("#support", "@support")
    }

    @Test
    fun `a token that can read one list offers that one and names the scope for the other`() {
        answering("conversations.list", channels("C0000000001" to "notifications"))
        answering("users.list", """{"ok":false,"error":"missing_scope","needed":"users:read"}""")

        val suggestions = directory().suggest(CONNECTION_ID, null, "not")

        // Somebody in this position used to be shown nothing at all, because
        // nothing could say which half to ask about. They are shown the half
        // that works, and told what would bring back the other.
        assertThat(suggestions.outcome).isEqualTo(SlackTargetOutcome.FOUND)
        assertThat(suggestions.matches.map { it.name }).containsExactly("#notifications")
        // Half a search is not a search, whatever it found.
        assertThat(suggestions.complete).isFalse()
        assertThat(suggestions.message).isEqualTo(
            "Channels only - this connection's bot token is missing the users:read scope.",
        )
        assertThat(suggestions.message.length).isLessThan(120)
    }

    @Test
    fun `nothing matching in the half that could be read is not a name that does not exist`() {
        answering("conversations.list", channels("C0000000001" to "general"))
        answering("users.list", """{"ok":false,"error":"missing_scope","needed":"users:read"}""")

        val suggestions = directory().suggest(CONNECTION_ID, null, "alice")

        assertThat(suggestions.outcome).isEqualTo(SlackTargetOutcome.UNCHECKED)
        assertThat(suggestions.outcome).isNotEqualTo(SlackTargetOutcome.NOT_FOUND)
        assertThat(suggestions.message).startsWith("Not checked").contains("users:read")
        assertThat(suggestions.message).doesNotContain("channels:read").doesNotContain("groups:read")
        assertThat(suggestions.message.length).isLessThan(120)
    }

    @Test
    fun `a token that can read neither list names both scopes`() {
        answering("conversations.list", """{"ok":false,"error":"missing_scope","needed":"channels:read"}""")
        answering("users.list", """{"ok":false,"error":"missing_scope","needed":"users:read"}""")

        val suggestions = directory().suggest(CONNECTION_ID, null, "al")

        assertThat(suggestions.outcome).isEqualTo(SlackTargetOutcome.UNCHECKED)
        assertThat(suggestions.matches).isEmpty()
        assertThat(suggestions.message)
            .startsWith("Not checked")
            .contains("the channels:read, groups:read and users:read scopes")
        assertThat(suggestions.message.length).isLessThan(120)
    }

    @Test
    fun `a name in neither list is a not-found that names both`() {
        answering("conversations.list", channels("C0000000001" to "general"))
        answering("users.list", members("U0000000001" to "alice"))

        val suggestions = directory().suggest(CONNECTION_ID, null, "nobody")

        assertThat(suggestions.outcome).isEqualTo(SlackTargetOutcome.NOT_FOUND)
        assertThat(suggestions.complete).isTrue()
        assertThat(suggestions.message).contains("No channel or member matches nobody")
        assertThat(suggestions.message.length).isLessThan(120)
    }

    @Test
    fun `a merged question reads the two lists that already exist, once each`() {
        answering("conversations.list", channels("C0000000001" to "alerts"))
        answering("users.list", members("U0000000001" to "alice"))
        val directory = directory()

        // A picker that was open on channels and is then asked for everything
        // pays for the member list and nothing else.
        directory.suggest(CONNECTION_ID, SlackTargetKind.CHANNEL, "a")
        directory.suggest(CONNECTION_ID, null, "al")
        val third = directory.suggest(CONNECTION_ID, null, "ali")

        // The cache is keyed by connection and kind, and a merged question asks
        // it for both of those keys rather than inventing a third that would
        // read everything a second time.
        assertThat(asked.count { it == "conversations.list" }).isEqualTo(1)
        assertThat(asked.count { it == "users.list" }).isEqualTo(1)
        assertThat(third.matches.map { it.name }).containsExactly("@alice")
    }

    @Test
    fun `a sigil narrows a merged question to the half it names`() {
        answering("conversations.list", channels("C0000000001" to "notifications"))
        answering("users.list", members("U0000000001" to "nobody"))

        val suggestions = directory().suggest(CONNECTION_ID, null, "#not")

        assertThat(suggestions.matches.map { it.name }).containsExactly("#notifications")
        assertThat(asked).contains("conversations.list").doesNotContain("users.list")
    }

    /** [SlackDirectory] as the application builds it, pointed at the stand-in. */
    private fun directory(
        secret: String? = SECRET,
        type: ConnectionType = ConnectionType.SLACK,
    ): SlackDirectory {
        val clients = SlackClients(ProxyRouter(ProxyRuleSource { emptyList() }))
        clients.webApi.config.methodsEndpointUrlPrefix = "http://${api.address.hostString}:${api.address.port}/api/"
        return SlackDirectory(connections(secret, type), clients)
    }

    private fun connections(secret: String?, type: ConnectionType): WorkspaceConnectionRepository {
        val connection = WorkspaceConnection(
            id = CONNECTION_ID,
            workspaceId = 1,
            name = "Support Slack",
            type = type,
            url = "https://slack.com",
            secret = secret,
        )
        val repository = mock(WorkspaceConnectionRepository::class.java)
        `when`(repository.findById(CONNECTION_ID)).thenReturn(Optional.of(connection))
        return repository
    }

    private fun answering(method: String, vararg bodies: String) {
        answers[method] = CopyOnWriteArrayList(bodies.toList())
    }

    /** Each answer once, and then the last one for ever. */
    private fun next(method: String): String {
        val queued = answers[method] ?: return """{"ok":true}"""
        return if (queued.size > 1) queued.removeAt(0) else queued.first()
    }

    private fun channels(vararg named: Pair<String, String>, cursor: String? = null): String =
        named.joinToString(",", """{"ok":true,"channels":[""", "]${metadata(cursor)}}") { (id, name) ->
            """{"id":"$id","name":"$name","name_normalized":"$name"}"""
        }

    private fun members(vararg named: Pair<String, String>, cursor: String? = null): String =
        named.joinToString(",", """{"ok":true,"members":[""", "]${metadata(cursor)}}") { (id, name) ->
            """{"id":"$id","name":"$name"}"""
        }

    private fun metadata(cursor: String?) =
        if (cursor == null) "" else ""","response_metadata":{"next_cursor":"$cursor"}"""

    private fun respond(exchange: HttpExchange, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
        exchange.close()
    }

    private companion object {
        const val CONNECTION_ID = 7L
        const val SECRET = "xoxb-not-a-real-token"

        /** `SlackDirectory.MOST_PAGES`, which is private and is a promise all the same. */
        const val MOST_PAGES = 10
    }
}
