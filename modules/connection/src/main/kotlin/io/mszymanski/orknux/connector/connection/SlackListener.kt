package io.mszymanski.orknux.connector.connection

import com.slack.api.bolt.App
import com.slack.api.bolt.AppConfig
import com.slack.api.bolt.socket_mode.SocketModeApp
import com.slack.api.model.event.AppMentionEvent
import com.slack.api.model.event.MessageBotEvent
import com.slack.api.model.event.MessageEvent
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

    private fun publish(connectionId: Long, workspaceId: Long, mention: AppMentionEvent, slackWorkspaceId: String?) {
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
                slackWorkspaceId?.let { put("slackWorkspaceId", it) }
            },
        )
        // Worth an INFO line: "did Slack deliver anything" is the first question
        // asked when a trigger does not fire, and answering it should not need
        // DEBUG on a third-party package. The text is left out — a mention is
        // someone's message, and this is not the place it gets stored.
        log.info(
            "Slack mention received on connection {} (workspace {}, channel {})",
            connectionId,
            workspaceId,
            mention.channel,
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
    fun receive(connectionId: Long, workspaceId: Long, message: MessageEvent, slackWorkspaceId: String?) {
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
    private fun deliver(connectionId: Long, workspaceId: Long, message: MessageEvent, slackWorkspaceId: String?) {
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
            slackWorkspaceId?.let { put("slackWorkspaceId", it) }
        }

        // The same INFO line a mention gets, and for the same reason: "did Slack
        // deliver anything" is the first question asked of a trigger that did
        // not fire. The text is left out - this is somebody's message, and this
        // is not the place it gets stored.
        log.info(
            "Slack message received on connection {} (workspace {}, channel {}, thread {})",
            connectionId,
            workspaceId,
            message.channel,
            message.threadTs,
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
    private fun ours(connectionId: Long, message: MessageEvent): Boolean {
        if (message.botId != null || message.botProfile != null) {
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
