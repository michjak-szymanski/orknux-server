package io.mszymanski.orknux.connector.connection

import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.domain.Sort
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * The connections one workspace holds: the admin defaults it was provisioned
 * with plus any it added itself. Credentials are always the workspace's own and are
 * never returned by a listing; [revealWorkspaceConnectionSecret] hands them over once
 * and logs that it did.
 *
 * Workspace visibility is orknux-server's to enforce — it knows the directory groups
 * and the connector does not — so a `workspaceId` that arrives here is already
 * allowed.
 */
@Service
class WorkspaceConnectionService(
    private val workspaceConnections: WorkspaceConnectionRepository,
    private val probe: ConnectionProbe,
    private val events: ApplicationEventPublisher,
) {

    fun workspaceConnections(workspaceId: Long): List<WorkspaceConnectionView> =
        workspaceConnections.findByWorkspaceId(workspaceId, Sort.by("name")).map(::WorkspaceConnectionView)

    fun workspaceConnection(id: Long): WorkspaceConnectionView? =
        workspaceConnections.findByIdOrNull(id)?.let(::WorkspaceConnectionView)

    @Transactional
    fun createWorkspaceConnection(input: CreateWorkspaceConnectionInput): WorkspaceConnectionView {
        val name = input.name.trim()
        val url = input.url.trim()
        if (name.isEmpty()) throw ConnectionNameInvalidException()
        if (url.isEmpty()) throw ConnectionUrlInvalidException()
        if (workspaceConnections.findByWorkspaceIdAndName(input.workspaceId, name) != null) {
            throw ConnectionNameTakenException(name)
        }

        val connection = workspaceConnections.save(
            WorkspaceConnection(
                workspaceId = input.workspaceId,
                name = name,
                type = input.type,
                url = url,
                authType = input.authType ?: AuthType.NONE,
                secret = input.secret?.trim()?.ifEmpty { null },
                appToken = input.appToken?.trim()?.ifEmpty { null },
                headers = input.headers.orEmpty().toHttpHeaders(),
            ),
        )
        // Checked as soon as the transaction lands, so a connection that was
        // just given a credential does not sit on "Not checked".
        events.publishEvent(WorkspaceConnectionSaved(requireNotNull(connection.id)))
        return WorkspaceConnectionView(connection)
    }

    /**
     * Backs the connection settings form. An inherited connection keeps the
     * default's name, type and URL; everything else is the workspace's to set.
     * A null secret leaves the stored credentials alone, an empty one clears them.
     */
    @Transactional
    fun updateWorkspaceConnection(id: Long, input: UpdateWorkspaceConnectionInput): WorkspaceConnectionView {
        val connection = workspaceConnections.findByIdOrNull(id) ?: throw ConnectionNotFoundException(id)

        if (!connection.inherited) {
            input.name?.trim()?.let { name ->
                if (name.isEmpty()) throw ConnectionNameInvalidException()
                if (name != connection.name && workspaceConnections.findByWorkspaceIdAndName(connection.workspaceId, name) != null) {
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

        events.publishEvent(WorkspaceConnectionSaved(id))
        return WorkspaceConnectionView(connection)
    }

    /**
     * Clears the workspace's credentials. A connection the workspace added itself has
     * nothing to fall back on, so it goes; an inherited one returns to the
     * admin default.
     */
    @Transactional
    fun disconnectWorkspaceConnection(id: Long): Boolean {
        val connection = workspaceConnections.findByIdOrNull(id) ?: return false

        if (connection.inherited) {
            connection.secret = null
            connection.appToken = null
            connection.urlOverride = null
            connection.headers = mutableListOf()
            connection.forgetLastCheck()
        } else {
            workspaceConnections.delete(connection)
        }
        return true
    }

    /**
     * Calls the other end and keeps what came back, so the workspace screen reports an
     * observation rather than the mere presence of a credential.
     */
    @Transactional
    fun testWorkspaceConnection(id: Long): WorkspaceConnectionView {
        val connection = workspaceConnections.findByIdOrNull(id) ?: throw ConnectionNotFoundException(id)

        val result = probe.check(connection.target(), connection.type)
        connection.lastCheckStatus = result.outcome
        connection.lastCheckMessage = result.message
        connection.lastCheckedAt = OffsetDateTime.now()
        return WorkspaceConnectionView(connection)
    }

    /**
     * Hands the stored credentials back, for the settings form's "Reveal" action.
     *
     * The audit entry belongs in orknux-server, where it can be attributed to
     * the person who asked; this log line is the connector's own record that a
     * credential left it.
     */
    @Transactional
    fun revealWorkspaceConnectionSecret(id: Long): String? {
        val connection = workspaceConnections.findByIdOrNull(id) ?: throw ConnectionNotFoundException(id)
        log.info("Credentials for connection {} (workspace {}) revealed", connection.name, connection.workspaceId)
        return connection.secret
    }

    private companion object {
        val log = LoggerFactory.getLogger(WorkspaceConnectionService::class.java)
    }
}

data class HttpHeaderInput(val name: String, val value: String)

/** Drops blank names so an empty row in the form does not become a header. */
fun List<HttpHeaderInput>.toHttpHeaders(): MutableList<HttpHeader> = this
    .map { HttpHeader(name = it.name.trim(), value = it.value.trim()) }
    .filter { it.name.isNotEmpty() }
    .toMutableList()

data class CreateWorkspaceConnectionInput(
    val workspaceId: Long,
    val name: String,
    val type: ConnectionType,
    val url: String,
    val authType: AuthType? = null,
    val secret: String? = null,
    /** Slack's Socket Mode app-level token, when the type wants one. */
    val appToken: String? = null,
    val headers: List<HttpHeaderInput>? = null,
)

data class UpdateWorkspaceConnectionInput(
    /** Ignored for inherited connections, which follow the admin default. */
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

data class WorkspaceConnectionView(
    val id: Long,
    val workspaceId: Long,
    val name: String,
    val type: ConnectionType,
    /** The admin default, or the workspace's own URL for a connection it added. */
    val url: String,
    val urlOverride: String?,
    /** Where requests actually go: the override when set, the default otherwise. */
    val effectiveUrl: String,
    val authType: AuthType,
    val headers: List<HttpHeaderView>,
    /** True while the workspace follows an admin default. */
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
    constructor(connection: WorkspaceConnection) : this(
        id = requireNotNull(connection.id),
        workspaceId = connection.workspaceId,
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
