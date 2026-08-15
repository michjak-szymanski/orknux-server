package io.mszymanski.gyloli.connector.connection

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
 * Listens to Slack over Socket Mode, one websocket per team connection that
 * carries an app-level token.
 *
 * Socket Mode dials out, so nothing here needs a public URL, an inbound
 * firewall rule or a request signature — which is what makes a self-hosted
 * gyloli able to receive Slack events at all.
 *
 * What arrives is published as an [IncomingEvent]; matching it to a trigger and
 * starting a workflow belongs to whoever owns those, not to this module.
 *
 * Connections change while the process runs — a team pastes a token, another
 * disconnects — so the set of sockets is reconciled on a timer rather than only
 * at startup. A connection whose credentials changed is closed and reopened,
 * since a session outlives the token it was opened with.
 */
@Component
@ConditionalOnProperty(prefix = "gyloli.slack", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class SlackListener(
    private val teamConnections: TeamConnectionRepository,
    private val events: ApplicationEventPublisher,
    private val properties: SlackProperties,
) {

    /** Open sockets by team connection id. */
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
        // Both Slack types can hold the two tokens; the socket-mode type is the
        // one whose form asks for them.
        val wanted = teamConnections.findByTypeIn(listOf(ConnectionType.SLACK_SOCKET_MODE, ConnectionType.SLACK))
            .filter { !it.appToken.isNullOrBlank() && !it.secret.isNullOrBlank() }
            .associateBy { requireNotNull(it.id) }

        for ((id, session) in sessions) {
            val connection = wanted[id]
            if (connection == null || connection.credentialsFingerprint() != session.fingerprint) {
                close(id)
            }
        }
        failures.keys.removeIf { it !in wanted }
        for ((id, connection) in wanted) {
            if (sessions.containsKey(id) || connection.waitingAfterFailure(failures[id])) continue
            open(id, connection)
        }
    }

    private fun open(id: Long, connection: TeamConnection) {
        val teamId = connection.teamId
        try {
            val app = App(AppConfig.builder().singleTeamBotToken(connection.secret).build())
            app.event(AppMentionEvent::class.java) { payload, context ->
                publish(id, teamId, payload.event, payload.teamId)
                context.ack()
            }

            // Tyrus is the websocket client the standalone bundle provides; the
            // JDK has none of its own.
            val socket = SocketModeApp(connection.appToken, SocketModeClient.Backend.Tyrus, app)
            socket.startAsync()
            sessions[id] = SlackSession(socket, connection.credentialsFingerprint())
            failures.remove(id)
            log.info("Listening to Slack on connection {} (team {})", connection.name, teamId)
        } catch (failure: Exception) {
            // A bad token, or Slack being unreachable. Neither is a failure of
            // the application, and neither is worth asking about every 30
            // seconds, so it waits — until the credentials change.
            failures[id] = FailedAttempt(connection.credentialsFingerprint(), Instant.now())
            log.warn(
                "Could not listen to Slack on connection {} (team {}): {}",
                connection.name,
                teamId,
                failure.message,
            )
        }
    }

    private fun publish(connectionId: Long, teamId: Long, mention: AppMentionEvent, slackTeamId: String?) {
        val event = IncomingEvent(
            connectionId = connectionId,
            teamId = teamId,
            action = IncomingAction.MENTION,
            text = mention.text,
            context = buildMap {
                mention.channel?.let { put("channel", it) }
                mention.user?.let { put("user", it) }
                mention.ts?.let { put("ts", it) }
                // Where a reply goes: the thread if there is one, else the message.
                (mention.threadTs ?: mention.ts)?.let { put("threadTs", it) }
                slackTeamId?.let { put("slackTeamId", it) }
            },
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

    /** True while the same credentials that just failed are still in their wait. */
    private fun TeamConnection.waitingAfterFailure(failure: FailedAttempt?): Boolean {
        if (failure == null) return false
        if (failure.fingerprint != credentialsFingerprint()) return false
        return failure.at.plusSeconds(properties.retryFailedSeconds).isAfter(Instant.now())
    }

    /**
     * Enough to tell that the credentials changed, without holding onto them:
     * a session opened with the old token has to be replaced.
     */
    private fun TeamConnection.credentialsFingerprint(): Int = arrayOf(appToken, secret).contentHashCode()

    private companion object {
        val log = LoggerFactory.getLogger(SlackListener::class.java)
    }
}
