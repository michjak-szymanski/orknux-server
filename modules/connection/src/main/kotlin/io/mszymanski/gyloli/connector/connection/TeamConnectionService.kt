package io.mszymanski.gyloli.connector.connection

import org.slf4j.LoggerFactory
import org.springframework.data.domain.Sort
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * The connections one team holds: the organization defaults it was provisioned
 * with plus any it added itself. Credentials are always the team's own and are
 * never returned by a listing; [revealTeamConnectionSecret] hands them over once
 * and logs that it did.
 *
 * Team visibility is gyloli-server's to enforce — it knows the directory groups
 * and the connector does not — so a `teamId` that arrives here is already
 * allowed.
 */
@Service
class TeamConnectionService(
    private val teamConnections: TeamConnectionRepository,
    private val probe: ConnectionProbe,
) {

    fun teamConnections(teamId: Long): List<TeamConnectionView> =
        teamConnections.findByTeamId(teamId, Sort.by("name")).map(::TeamConnectionView)

    fun teamConnection(id: Long): TeamConnectionView? =
        teamConnections.findByIdOrNull(id)?.let(::TeamConnectionView)

    @Transactional
    fun createTeamConnection(input: CreateTeamConnectionInput): TeamConnectionView {
        val name = input.name.trim()
        val url = input.url.trim()
        if (name.isEmpty()) throw ConnectionNameInvalidException()
        if (url.isEmpty()) throw ConnectionUrlInvalidException()
        if (teamConnections.findByTeamIdAndName(input.teamId, name) != null) {
            throw ConnectionNameTakenException(name)
        }

        val connection = teamConnections.save(
            TeamConnection(
                teamId = input.teamId,
                name = name,
                type = input.type,
                url = url,
                authType = input.authType ?: AuthType.NONE,
                secret = input.secret?.trim()?.ifEmpty { null },
                appToken = input.appToken?.trim()?.ifEmpty { null },
                headers = input.headers.orEmpty().toHttpHeaders(),
            ),
        )
        return TeamConnectionView(connection)
    }

    /**
     * Backs the connection settings form. An inherited connection keeps the
     * organization's name, type and URL; everything else is the team's to set.
     * A null secret leaves the stored credentials alone, an empty one clears them.
     */
    @Transactional
    fun updateTeamConnection(id: Long, input: UpdateTeamConnectionInput): TeamConnectionView {
        val connection = teamConnections.findByIdOrNull(id) ?: throw ConnectionNotFoundException(id)

        if (!connection.inherited) {
            input.name?.trim()?.let { name ->
                if (name.isEmpty()) throw ConnectionNameInvalidException()
                if (name != connection.name && teamConnections.findByTeamIdAndName(connection.teamId, name) != null) {
                    throw ConnectionNameTakenException(name)
                }
                connection.name = name
            }
            input.type?.let { connection.type = it }
            input.url?.trim()?.let { url ->
                if (url.isEmpty()) throw ConnectionUrlInvalidException()
                connection.url = url
            }
        }

        input.authType?.let { connection.authType = it }
        input.urlOverride?.let { connection.urlOverride = it.trim().ifEmpty { null } }
        input.secret?.let { connection.secret = it.trim().ifEmpty { null } }
        input.appToken?.let { connection.appToken = it.trim().ifEmpty { null } }
        input.headers?.let { connection.headers = it.toHttpHeaders() }
        // Whatever the last probe found described the old configuration.
        connection.forgetLastCheck()

        return TeamConnectionView(connection)
    }

    /**
     * Clears the team's credentials. A connection the team added itself has
     * nothing to fall back on, so it goes; an inherited one returns to the
     * organization default.
     */
    @Transactional
    fun disconnectTeamConnection(id: Long): Boolean {
        val connection = teamConnections.findByIdOrNull(id) ?: return false

        if (connection.inherited) {
            connection.secret = null
            connection.appToken = null
            connection.urlOverride = null
            connection.headers = mutableListOf()
            connection.forgetLastCheck()
        } else {
            teamConnections.delete(connection)
        }
        return true
    }

    /**
     * Calls the other end and keeps what came back, so the team screen reports an
     * observation rather than the mere presence of a credential.
     */
    @Transactional
    fun testTeamConnection(id: Long): TeamConnectionView {
        val connection = teamConnections.findByIdOrNull(id) ?: throw ConnectionNotFoundException(id)

        val result = probe.check(connection.target())
        connection.lastCheckStatus = result.outcome
        connection.lastCheckMessage = result.message
        connection.lastCheckedAt = OffsetDateTime.now()
        return TeamConnectionView(connection)
    }

    /**
     * Hands the stored credentials back, for the settings form's "Reveal" action.
     *
     * The audit entry belongs in gyloli-server, where it can be attributed to
     * the person who asked; this log line is the connector's own record that a
     * credential left it.
     */
    @Transactional
    fun revealTeamConnectionSecret(id: Long): String? {
        val connection = teamConnections.findByIdOrNull(id) ?: throw ConnectionNotFoundException(id)
        log.info("Credentials for connection {} (team {}) revealed", connection.name, connection.teamId)
        return connection.secret
    }

    private companion object {
        val log = LoggerFactory.getLogger(TeamConnectionService::class.java)
    }
}

data class HttpHeaderInput(val name: String, val value: String)

/** Drops blank names so an empty row in the form does not become a header. */
fun List<HttpHeaderInput>.toHttpHeaders(): MutableList<HttpHeader> = this
    .map { HttpHeader(name = it.name.trim(), value = it.value.trim()) }
    .filter { it.name.isNotEmpty() }
    .toMutableList()

data class CreateTeamConnectionInput(
    val teamId: Long,
    val name: String,
    val type: ConnectionType,
    val url: String,
    val authType: AuthType? = null,
    val secret: String? = null,
    /** Slack's Socket Mode app-level token, when the type wants one. */
    val appToken: String? = null,
    val headers: List<HttpHeaderInput>? = null,
)

data class UpdateTeamConnectionInput(
    /** Ignored for inherited connections, which follow the organization default. */
    val name: String? = null,
    val type: ConnectionType? = null,
    val url: String? = null,
    val urlOverride: String? = null,
    val authType: AuthType? = null,
    /** Null leaves the stored credentials alone; empty clears them. */
    val secret: String? = null,
    /** The Socket Mode app-level token, with the same null and empty meaning. */
    val appToken: String? = null,
    val headers: List<HttpHeaderInput>? = null,
)

data class HttpHeaderView(val name: String, val value: String)

data class TeamConnectionView(
    val id: Long,
    val teamId: Long,
    val name: String,
    val type: ConnectionType,
    /** The organization default, or the team's own URL for a connection it added. */
    val url: String,
    val urlOverride: String?,
    /** Where requests actually go: the override when set, the default otherwise. */
    val effectiveUrl: String,
    val authType: AuthType,
    val headers: List<HttpHeaderView>,
    /** True while the team follows an organization default. */
    val inherited: Boolean,
    val secretSet: Boolean,
    /** Whether an app-level token is stored, which is what opens a listening socket. */
    val appTokenSet: Boolean,
    val status: ConnectionStatus,
    /** What the last probe reported, for the settings screen. */
    val lastCheckMessage: String?,
    /** ISO-8601 offset date-time, or null when no probe has run. */
    val lastCheckedAt: String?,
) {
    constructor(connection: TeamConnection) : this(
        id = requireNotNull(connection.id),
        teamId = connection.teamId,
        name = connection.name,
        type = connection.type,
        url = connection.url,
        urlOverride = connection.urlOverride,
        effectiveUrl = connection.effectiveUrl,
        authType = connection.authType,
        headers = connection.headers.map { HttpHeaderView(it.name, it.value) },
        inherited = connection.inherited,
        secretSet = !connection.secret.isNullOrBlank(),
        appTokenSet = !connection.appToken.isNullOrBlank(),
        status = connection.status,
        lastCheckMessage = connection.lastCheckMessage,
        lastCheckedAt = connection.lastCheckedAt?.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
    )
}
