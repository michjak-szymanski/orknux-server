package io.mszymanski.orknux.connector.connection

import com.slack.api.bolt.App
import com.slack.api.bolt.AppConfig
import com.slack.api.bolt.socket_mode.SocketModeApp
import com.slack.api.model.event.AppMentionEvent
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

        dispatcher.execute {
            try {
                events.publishEvent(event)
            } catch (failure: Exception) {
                // Nobody is left to tell: the acknowledgement has gone back to
                // Slack already, and this thread is the end of the line.
                log.error("A Slack mention on connection {} could not be handled", connectionId, failure)
            }
        }
    }

    private fun close(id: Long) {
        val session = sessions.remove(id) ?: return
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
