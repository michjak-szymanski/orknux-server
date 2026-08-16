package io.mszymanski.orknux.connector.connection

import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Embeddable
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.OrderColumn
import jakarta.persistence.Table
import java.time.OffsetDateTime

/** The external services a connection can point at. */
enum class ConnectionType {
    SLACK,

    /**
     * Slack over Socket Mode: a bot token to call the API with and an
     * app-level token to open the websocket. This is the type orknux listens
     * on, and the one that asks for both credentials.
     */
    SLACK_SOCKET_MODE,
    GITHUB,
    JIRA,
    TEAMS,

    /** Anything that is just an HTTP endpoint, until it earns a type of its own. */
    WEBHOOK,
}

/** What the workspace screen reports about a connection. */
enum class ConnectionStatus {
    /** No credentials stored, so there is nothing to check. */
    NOT_CONFIGURED,

    /** Configured, but no probe has reached the other end yet. */
    NOT_CHECKED,
    CONNECTED,
    FAILED,
}

/** How a request to the service is authenticated. */
enum class AuthType {
    NONE,
    API_KEY,
    BEARER_TOKEN,
    BASIC,
}

/** An extra HTTP header sent with every request. */
@Embeddable
class HttpHeader(
    @Column(name = "name", nullable = false)
    var name: String = "",

    @Column(name = "value", nullable = false, length = 1000)
    var value: String = "",
)

/**
 * An admin-level default connection. New workspaces are provisioned with a copy
 * of every default; the credentials are always the workspace's own.
 */
@Entity
@Table(name = "connection")
class Connection(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(nullable = false)
    var name: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    var type: ConnectionType,

    @Column(nullable = false, length = 1000)
    var url: String,
)

/**
 * A connection as one workspace holds it, with that workspace's credentials.
 *
 * [workspaceId] names a workspace in orknux-server; there is no foreign key to enforce it
 * and no cascade to clean it up, so a deleted workspace is forgotten only when the
 * server tells the connector about it.
 */
@Entity
@Table(name = "workspace_connection")
class WorkspaceConnection(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(nullable = false)
    val workspaceId: Long,

    /** The admin default this was provisioned from; null when workspace-owned. */
    @Column(name = "connection_id")
    var connectionId: Long? = null,

    @Column(nullable = false)
    var name: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    var type: ConnectionType,

    @Column(nullable = false, length = 1000)
    var url: String,

    /** Used instead of [url] when the workspace points at its own endpoint. */
    @Column(name = "url_override", length = 1000)
    var urlOverride: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "auth_type", nullable = false, length = 16)
    var authType: AuthType = AuthType.NONE,

    @Column(length = 1000)
    var secret: String? = null,

    /**
     * A second credential, for services that need one to open a listening
     * socket: Slack's Socket Mode wants an app-level token (`xapp-...`) as well
     * as the bot token that [secret] holds.
     */
    @Column(name = "app_token", length = 1000)
    var appToken: String? = null,

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
        name = "workspace_connection_header",
        joinColumns = [JoinColumn(name = "workspace_connection_id")],
    )
    @OrderColumn(name = "position")
    var headers: MutableList<HttpHeader> = mutableListOf(),

    /** What the last probe found; null until one has run against this configuration. */
    @Enumerated(EnumType.STRING)
    @Column(name = "last_check_status", length = 16)
    var lastCheckStatus: CheckOutcome? = null,

    @Column(name = "last_check_message", length = 500)
    var lastCheckMessage: String? = null,

    @Column(name = "last_checked_at")
    var lastCheckedAt: OffsetDateTime? = null,
) {

    /** Inherited connections take their name, type and URL from the default. */
    val inherited: Boolean get() = connectionId != null

    val effectiveUrl: String get() = urlOverride?.takeIf { it.isNotBlank() } ?: url

    /**
     * Whether anything has been configured to authenticate with. Socket Mode
     * needs both credentials: one opens the socket, the other answers on it.
     */
    val configured: Boolean
        get() = when (type) {
            ConnectionType.SLACK_SOCKET_MODE -> !secret.isNullOrBlank() && !appToken.isNullOrBlank()
            else -> authType == AuthType.NONE || !secret.isNullOrBlank()
        }

    /**
     * What the workspace screen reports. A connection is only [ConnectionStatus.CONNECTED]
     * once a probe has reached the other end with this configuration.
     */
    val status: ConnectionStatus
        get() = when {
            !configured -> ConnectionStatus.NOT_CONFIGURED
            lastCheckStatus == CheckOutcome.CONNECTED -> ConnectionStatus.CONNECTED
            lastCheckStatus == CheckOutcome.FAILED -> ConnectionStatus.FAILED
            else -> ConnectionStatus.NOT_CHECKED
        }

    /** Called whenever the configuration changes, since the old result no longer describes it. */
    fun forgetLastCheck() {
        lastCheckStatus = null
        lastCheckMessage = null
        lastCheckedAt = null
    }
}

/** An MCP server a workspace's agents may connect to. */
@Entity
@Table(name = "mcp_server")
class McpServer(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(nullable = false)
    val workspaceId: Long,

    @Column(nullable = false)
    var name: String,

    @Column(nullable = false, length = 1000)
    var address: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "auth_type", nullable = false, length = 16)
    var authType: AuthType = AuthType.NONE,

    @Column(length = 1000)
    var secret: String? = null,

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "mcp_server_header", joinColumns = [JoinColumn(name = "mcp_server_id")])
    @OrderColumn(name = "position")
    var headers: MutableList<HttpHeader> = mutableListOf(),
)
