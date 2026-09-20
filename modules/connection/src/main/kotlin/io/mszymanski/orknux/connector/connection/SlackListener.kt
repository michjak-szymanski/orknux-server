package io.mszymanski.orknux.connector.connection

import com.slack.api.bolt.App
import com.slack.api.bolt.AppConfig
import com.slack.api.bolt.socket_mode.SocketModeApp
import com.slack.api.model.event.AppMentionEvent
import com.slack.api.model.event.MessageBotEvent
import com.slack.api.model.event.MessageEvent
import com.slack.api.model.event.MessageFileShareEvent
import com.slack.api.socket_mode.SocketModeClient
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Listens to Slack over Socket Mode, one websocket per workspace connection that
 * carries an app-level token.
 *
 * Socket Mode dials out, so nothing here needs a public URL, an inbound
 * firewall rule or a request signature — which is what makes a self-hosted
 * orknux able to receive Slack events at all. Dialling out is still an outbound
 * call, so it goes through the proxy rules like any other; [SlackClients] is
 * how, and why it takes two lines here rather than one.
 *
 * What arrives is published as an [IncomingEvent]; matching it to a trigger and
 * starting a workflow belongs to whoever owns those, not to this module.
 *
 * **Three things are listened for**: a mention, a message in any channel the bot
 * is a member of, and a thread reply, which is a message with a thread on it.
 * The last two are a different order of traffic from the first — a mention is
 * addressed to us and a message is everything anybody types — and they need
 * `channels:history` on the bot token, plus `groups:`, `im:` and `mpim:` for the
 * other three kinds of conversation, plus the matching `message.*` subscriptions
 * on the Slack app. A token without them opens the socket perfectly and hears
 * nothing; `SlackBotUsers` is what says so in one line.
 *
 * Connections change while the process runs — a workspace pastes a token, another
 * disconnects — so the set of sockets is reconciled on a timer rather than only
 * at startup. A connection whose credentials changed is closed and reopened,
 * since a session outlives the token it was opened with.
 */
@Component
@ConditionalOnProperty(prefix = "orknux.slack", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class SlackListener(
    private val workspaceConnections: WorkspaceConnectionRepository,
    private val events: ApplicationEventPublisher,
    private val properties: SlackProperties,
    /** Where the two tokens come from: the connection's own copies, or workspace secrets. */
    private val credentials: ConnectionCredentials,
    private val slackClients: SlackClients,
    /** Who each connection posts as, which is how a reply to one of ours is known. */
    private val botUsers: SlackBotUsers,
) {

    /** Open sockets by workspace connection id. */
    private val sessions = ConcurrentHashMap<Long, SlackSession>()

    /** The credentials that would not open, so they are not tried on every pass. */
    private val failures = ConcurrentHashMap<Long, FailedAttempt>()

    private val reconciler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "slack-listener").apply { isDaemon = true }
    }

    /**
     * Slack wants an acknowledgement within three seconds, and what a mention
     * sets off is a workflow run, so the socket thread hands the event on and
     * goes back to reading.
     */
    private val dispatcher = Executors.newVirtualThreadPerTaskExecutor()

    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        reconciler.scheduleWithFixedDelay(
            { runCatching(::reconcile).onFailure { log.warn("Could not reconcile Slack listeners", it) } },
            0,
            properties.reconcileSeconds,
            TimeUnit.SECONDS,
        )
    }

    /**
     * Brings the open sockets in line with what the connections now say.
     *
     * Visible for the sake of a caller that has just changed a connection and
     * would rather not wait for the timer.
     */
    fun reconcile() {
        /*
         * The app-level token is what decides this, not the type: a Slack
         * connection given one listens, one left without it only sends.
         *
         * Resolved rather than read off the row, because either token may be a
         * workspace secret now. It is also what the fingerprint is taken over,
         * so rotating the variable closes the session that was opened with the
         * old value - fingerprinting the columns instead would leave a socket
         * running on a token nobody uses any more, silently, until a restart.
         */
        val wanted = workspaceConnections.findByType(ConnectionType.SLACK)
            .mapNotNull { connection -> listening(connection)?.let { requireNotNull(connection.id) to it } }
            .toMap()

        for ((id, session) in sessions) {
            if (wanted[id]?.fingerprint != session.fingerprint) close(id)
        }
        failures.keys.removeIf { it !in wanted }
        for ((id, connection) in wanted) {
            if (sessions.containsKey(id) || connection.waitingAfterFailure(failures[id])) continue
            open(id, connection)
        }
    }

    /**
     * One connection with both its tokens in hand, or null when it is not one
     * that listens - no bot token, no app-level token, or a reference to a
     * workspace secret that has gone or was never filled in.
     */
    private fun listening(connection: WorkspaceConnection): Listening? {
        val bot = credentials.secretOf(connection).credential ?: return null
        val app = credentials.appTokenOf(connection).credential ?: return null
        return Listening(connection, bot, app)
    }

    private fun open(id: Long, connection: Listening) {
        val workspaceId = connection.workspaceId
        try {
            // The Slack instance the app is built on is the one the whole
            // session runs through - the `apps.connections.open` that issues the
            // websocket URL, every call a handler makes, and the socket itself.
            // Giving it one that consults the proxy rules is what puts Slack
            // under the same rules as everything else outbound.
            val routed = slackClients.forSocketMode()
            val app = App(
                AppConfig.builder()
                    .singleTeamBotToken(connection.botToken)
                    .slack(routed.slack)
                    .build(),
            )
            app.event(AppMentionEvent::class.java) { payload, context ->
                publish(id, workspaceId, payload.event, payload.teamId)
                context.ack()
            }

            /*
             * Everything anyone types in a channel this bot can read.
             *
             * A mention is addressed to us and a message is not, which is the
             * difference worth keeping in mind when reading the volume: this
             * arrives once per message in every channel the bot is a member of,
             * for as long as the token carries `channels:history`. What keeps
             * that affordable is that the work is a repository query against the
             * trigger catalogue and nothing more until something matches.
             */
            app.event(MessageEvent::class.java) { payload, context ->
                receive(id, workspaceId, payload.event, payload.teamId)
                context.ack()
            }

            /*
             * A message that carries a file, which Slack delivers as its own
             * kind of event.
             *
             * Nothing was registered for it, so Bolt answered every upload with
             * `no handler found` and the message was dropped on the floor -
             * with its text, its thread and its file. What that looked like
             * from a Slack channel is somebody attaching a PDF, asking the bot
             * about it, and the bot replying that it has no PDF: the mention
             * arrived, the upload never did, and the agent was telling the
             * truth about what it had been given.
             *
             * The same path as an ordinary message, because that is what it is
             * - `message` with a `file_share` subtype - and the same loop
             * guard applies to it.
             */
            app.event(MessageFileShareEvent::class.java) { payload, context ->
                receive(id, workspaceId, payload.event, payload.teamId)
                context.ack()
            }

            /*
             * A bot's message, acknowledged and dropped.
             *
             * Registered rather than left unhandled so that the drop is written
             * down where somebody looks for it, and so the SDK does not log a
             * missing handler for every one. See [ours] for why a message from a
             * bot is never published: a workflow that answers in a thread it
             * watches would otherwise trigger itself, for ever.
             */
            app.event(MessageBotEvent::class.java) { _, context -> context.ack() }

            // Tyrus is the websocket client the standalone bundle provides; the
            // JDK has none of its own. It takes a proxy, but only one address
            // and only when it connects, so it is pointed at the URL Slack has
            // by then issued this session rather than at a rule chosen now.
            val socket = SocketModeApp(connection.appToken, SocketModeClient.Backend.Tyrus, app)
            routed.routeAgainst { socket.client?.wssUri?.toString() }
            socket.startAsync()
            sessions[id] = SlackSession(socket, connection.fingerprint)
            failures.remove(id)
            log.info("Listening to Slack on connection {} (workspace {})", connection.name, workspaceId)
        } catch (failure: Exception) {
            // A bad token, or Slack being unreachable. Neither is a failure of
            // the application, and neither is worth asking about every 30
            // seconds, so it waits — until the credentials change.
            failures[id] = FailedAttempt(connection.fingerprint, Instant.now())
            log.warn(
                "Could not listen to Slack on connection {} (workspace {}): {}",
                connection.name,
                workspaceId,
                failure.message,
            )
        }
    }

    /**
     * A mention, on its way to whoever is watching for one.
     *
     * Public for the reason [receive] is: a socket is the only other caller,
     * and a test that had to open one could not run without Slack.
     */
    fun publish(connectionId: Long, workspaceId: Long, mention: AppMentionEvent, slackWorkspaceId: String?) {
        val event = IncomingEvent(
            connectionId = connectionId,
            workspaceId = workspaceId,
            action = IncomingAction.MENTION,
            text = mention.text,
            context = buildMap {
                mention.channel?.let { put("channel", it) }
                mention.user?.let { put("user", it) }
                mention.ts?.let { put("ts", it) }
                // Where a reply goes: the thread if there is one, else the message.
                (mention.threadTs ?: mention.ts)?.let { put("threadTs", it) }
                // The same as for a message: which Slack this came from.
                put("connection", connectionId.toString())
                slackWorkspaceId?.let { put("slackWorkspaceId", it) }
                // And what was attached to it, where anything was.
                describe(mention.files)?.let { put("files", it) }
            },
        )
        // Worth an INFO line: "did Slack deliver anything" is the first question
        // asked when a trigger does not fire, and answering it should not need
        // DEBUG on a third-party package. The text is left out — a mention is
        // someone's message, and this is not the place it gets stored.
        log.info(
            "Slack mention received on connection {} (workspace {}, channel {}, {} file(s))",
            connectionId,
            workspaceId,
            mention.channel,
            filesOf(mention.files).size,
        )

        raise(connectionId, event)
    }

    /**
     * A message in a channel this connection can read.
     *
     * **Two events can come of one message.** [IncomingAction.MESSAGE] is raised
     * for every message that is not a bot's, and [IncomingAction.REPLY] as well
     * when it hangs under a thread — a reply is a message, and a definition
     * waiting on messages in a channel should not stop hearing them because
     * somebody used a thread. Which of the two a trigger wants is the trigger's
     * choice, and the two are matched separately.
     *
     * **Slack sends a mention twice.** An `@orknux` in a channel the bot reads
     * arrives as `app_mention` and again as `message`, so a connection carrying
     * a mention trigger and a message trigger fires both. That is Slack's own
     * doing rather than something to correct here — a message trigger that
     * silently skipped mentions would be the more surprising of the two.
     *
     * Public for the same reason [listeningConnectionIds] is: a socket is the
     * only other caller, and a test that had to open one could not run without
     * Slack. A real payload put through here is the whole path bar the wire.
     */
    fun receive(connectionId: Long, workspaceId: Long, message: MessageEvent, slackWorkspaceId: String?) =
        receive(connectionId, workspaceId, said(message), slackWorkspaceId)

    /**
     * The same, for the upload Slack delivers as its own event.
     *
     * A `message` with a `file_share` subtype, which the SDK models as a
     * different class carrying the same fields - so it is turned into the same
     * [Said] and walks the same path.
     */
    fun receive(
        connectionId: Long,
        workspaceId: Long,
        message: MessageFileShareEvent,
        slackWorkspaceId: String?,
    ) = receive(connectionId, workspaceId, said(message), slackWorkspaceId)

    private fun receive(connectionId: Long, workspaceId: Long, message: Said, slackWorkspaceId: String?) {
        /*
         * Handed on whole, rather than filtered here and handed on after.
         *
         * The loop guard asks who this connection posts as, and on a cold cache
         * that is a call to Slack — which must not stand between an arriving
         * message and the acknowledgement Slack wants inside three seconds. A
         * mention can be filtered on the socket thread because there is nothing
         * to ask about one; this cannot.
         */
        dispatcher.execute {
            try {
                deliver(connectionId, workspaceId, message, slackWorkspaceId)
            } catch (failure: Exception) {
                // Nobody is left to tell: the acknowledgement has gone back to
                // Slack already, and this thread is the end of the line.
                log.error("A Slack message on connection {} could not be handled", connectionId, failure)
            }
        }
    }

    /** One message, already off the socket thread. */
    private fun deliver(connectionId: Long, workspaceId: Long, message: Said, slackWorkspaceId: String?) {
        if (ours(connectionId, message)) return

        val context = buildMap {
            message.channel?.let { put("channel", it) }
            message.user?.let { put("user", it) }
            message.ts?.let { put("ts", it) }
            // Where a reply goes: the thread if there is one, else the message.
            (message.threadTs ?: message.ts)?.let { put("threadTs", it) }
            // Who wrote the message this hangs under, which is the whole of how
            // "a reply to one of ours" is decided. Only a thread reply has one.
            message.parentUserId?.let { put("parentUserId", it) }
            // `channel`, `im`, `mpim`, `group` - what a workflow reads to tell a
            // direct message from a channel, which the channel id does not say.
            message.channelType?.let { put("channelType", it) }
            /*
             * Which connection this arrived on.
             *
             * So a later node can answer back, or read the thread, through the
             * one it came from rather than through whichever the workspace
             * happens to list first: two Slack connections are two Slacks, and
             * the difference only shows up as somebody else's messages.
             */
            put("connection", connectionId.toString())
            slackWorkspaceId?.let { put("slackWorkspaceId", it) }
            // What was attached, where anything was. See [describe].
            describe(message.files)?.let { put("files", it) }
        }

        // The same INFO line a mention gets, and for the same reason: "did Slack
        // deliver anything" is the first question asked of a trigger that did
        // not fire. The text is left out - this is somebody's message, and this
        // is not the place it gets stored.
        log.info(
            "Slack message received on connection {} (workspace {}, channel {}, thread {}, {} file(s))",
            connectionId,
            workspaceId,
            message.channel,
            message.threadTs,
            // Counted rather than named: "did the upload arrive" is the first
            // question asked when an agent says it cannot see a file, and
            // answering it should not need DEBUG on somebody else's package.
            // The names are somebody's filenames and do not belong in a log.
            message.files.size,
        )

        events.publishEvent(IncomingEvent(connectionId, workspaceId, IncomingAction.MESSAGE, message.text, context))
        if (message.threadTs != null) {
            events.publishEvent(IncomingEvent(connectionId, workspaceId, IncomingAction.REPLY, message.text, context))
        }
    }

    /**
     * Whether this is something we wrote, and therefore not to be published.
     *
     * **The loop guard, and it is not optional.** A workflow that answers in a
     * thread it watches sees its own answer arrive as a reply to a message one
     * of our bots wrote — which is exactly what a reply trigger is looking for —
     * and starts itself again, and again.
     *
     * Two questions rather than one because Slack answers in two ways. A message
     * posted through the API carries a `bot_id` naming whoever posted it, which
     * catches every bot including other people's. And where an app posts as its
     * own bot user without one, the author is that user, so the connection's own
     * id is compared as well — resolved from the cache [SlackBotUsers] keeps,
     * never from a call made per message.
     */
    private fun ours(connectionId: Long, message: Said): Boolean {
        if (message.botId != null || message.fromBotProfile) {
            log.debug("A Slack message on connection {} came from a bot and was left alone", connectionId)
            return true
        }
        val author = message.user ?: return true
        if (author == botUsers.identify(connectionId).userId) {
            log.debug("A Slack message on connection {} was this connection's own", connectionId)
            return true
        }
        return false
    }

    /**
     * One message, whichever of Slack's events delivered it.
     *
     * An ordinary message, a mention and an upload are three classes in the SDK
     * with the same fields on them, and everything downstream of here cares
     * about the fields. Written out rather than handled three times: the loop
     * guard and the context are the two things that must not differ between
     * them, and they differed the day one of the three was simply not
     * registered.
     */
    private data class Said(
        val channel: String?,
        val user: String?,
        val ts: String?,
        val threadTs: String?,
        val parentUserId: String?,
        val channelType: String?,
        val text: String?,
        val files: List<com.slack.api.model.File>,
        /** Named by Slack when the API posted it; see [ours]. */
        val botId: String?,
        /** The other way Slack says the same thing. */
        val fromBotProfile: Boolean,
    )

    /**
     * The files on an event, which Slack leaves out rather than sending empty.
     *
     * Its own function because the getter is a platform type: reading it into
     * anything non-null compiles to a check that throws, and `orEmpty()` on
     * the value does not save it. Taken as nullable here, so the null is
     * handled where it arrives instead of where it lands.
     */
    private fun filesOf(files: List<com.slack.api.model.File>?): List<com.slack.api.model.File> =
        files ?: emptyList()

    private fun said(message: MessageEvent) = Said(
        channel = message.channel,
        user = message.user,
        ts = message.ts,
        threadTs = message.threadTs,
        parentUserId = message.parentUserId,
        channelType = message.channelType,
        text = message.text,
        files = filesOf(message.files),
        botId = message.botId,
        fromBotProfile = message.botProfile != null,
    )

    /**
     * An upload, read the same way.
     *
     * No `bot_id` on this one: Slack does not put it on a file share, so what
     * catches our own upload is the author check in [ours] - a file this
     * installation posted was posted as the connection's own bot user, which
     * that question already asks about.
     */
    private fun said(message: MessageFileShareEvent) = Said(
        channel = message.channel,
        user = message.user,
        ts = message.ts,
        threadTs = message.threadTs,
        parentUserId = message.parentUserId,
        channelType = message.channelType,
        text = message.text,
        files = filesOf(message.files),
        botId = null,
        fromBotProfile = false,
    )

    /**
     * What was attached, as a line a model and a workflow can both read.
     *
     * The bytes are not here and should not be: a file is fetched through the
     * Slack plugin, with the token, when something decides it wants it. What
     * this carries is enough to decide - the id `slack_readAttachment` takes,
     * the name, the type and the size - because the alternative is what
     * happened before it: an agent handed a message with no sign that anything
     * came with it, answering questions about a document it had never been
     * told existed.
     *
     * JSON, so a workflow expression can read a field out of it, and compact,
     * because this rides in the payload a model sees. Null where nothing was
     * attached, which keeps the key off every ordinary message.
     */
    private fun describe(files: List<com.slack.api.model.File>?): String? {
        /*
         * Null, not empty, for a message with nothing attached.
         *
         * Slack leaves the field out and the SDK's getter is a platform type,
         * so a `List<File>` parameter here compiles to a null check that
         * throws on every ordinary message - which is exactly what it did:
         * one NullPointerException per mention, inside the dispatcher, and no
         * trigger fired at all. Nullable at the door, once, rather than
         * remembered at each call.
         */
        if (files.isNullOrEmpty()) return null
        return files.joinToString(",", "[", "]") { file ->
            buildString {
                append("{")
                append("\"id\":\"").append(quoted(file.id)).append("\",")
                append("\"name\":\"").append(quoted(file.name ?: file.title)).append("\",")
                append("\"mimetype\":\"").append(quoted(file.mimetype)).append("\",")
                append("\"size\":").append(file.size ?: 0)
                append("}")
            }
        }
    }

    /** A filename is somebody else's text, and it lands in JSON. */
    private fun quoted(value: String?): String =
        value.orEmpty().replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ")

    /** Off the socket thread, so Slack's three seconds are not spent on a workflow. */
    private fun raise(connectionId: Long, event: IncomingEvent) {
        dispatcher.execute {
            try {
                events.publishEvent(event)
            } catch (failure: Exception) {
                // Nobody is left to tell: the acknowledgement has gone back to
                // Slack already, and this thread is the end of the line.
                log.error("A Slack {} on connection {} could not be handled", event.action, connectionId, failure)
            }
        }
    }

    private fun close(id: Long) {
        val session = sessions.remove(id) ?: return
        // A session is closed because the credentials changed, and a new token
        // may well be a different Slack user. Asked again rather than assumed.
        botUsers.forget(id)
        runCatching { session.socket.close() }
            .onFailure { log.warn("Could not close the Slack socket for connection {}", id, it) }
    }

    @PreDestroy
    fun stop() {
        reconciler.shutdownNow()
        dispatcher.shutdown()
        sessions.keys.toList().forEach(::close)
    }

    /** Which connections are listening, for the monitoring screen and the tests. */
    fun listeningConnectionIds(): Set<Long> = sessions.keys.toSet()

    private class SlackSession(val socket: SocketModeApp, val fingerprint: Int)

    private class FailedAttempt(val fingerprint: Int, val at: Instant)

    /**
     * A connection that listens, with the two tokens it listens by.
     *
     * Not a data class, and [toString] carries neither token: a generated one
     * would put both into every log line that ever interpolated the object.
     */
    private class Listening(connection: WorkspaceConnection, val botToken: String, val appToken: String) {
        val name: String = connection.name
        val workspaceId: Long = connection.workspaceId

        /**
         * Enough to tell that the credentials changed, without holding onto them
         * anywhere they outlive the pass: a session opened with the old token
         * has to be replaced.
         */
        val fingerprint: Int = arrayOf(botToken, appToken).contentHashCode()

        override fun toString(): String = "Listening($name)"
    }

    /** True while the same credentials that just failed are still in their wait. */
    private fun Listening.waitingAfterFailure(failure: FailedAttempt?): Boolean {
        if (failure == null) return false
        if (failure.fingerprint != fingerprint) return false
        return failure.at.plusSeconds(properties.retryFailedSeconds).isAfter(Instant.now())
    }

    private companion object {
        val log = LoggerFactory.getLogger(SlackListener::class.java)
    }
}
