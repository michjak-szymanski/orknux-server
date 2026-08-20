package io.mszymanski.orknux.connector.connection

import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.Convert
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
import io.mszymanski.orknux.connector.security.SECRET_COLUMN_LENGTH
import io.mszymanski.orknux.connector.security.SecretCipher
import io.mszymanski.orknux.connector.security.SecretConverter
import java.time.OffsetDateTime

/**
 * Where Slack's Web API lives.
 *
 * A constant rather than something the form asks for: there is one Slack, it has
 * one API base, and a person setting up a connection has no useful other answer.
 * It is only ever used by [ConnectionProbe], which calls `auth.test` under it -
 * [OutgoingMessages] posts through the Slack SDK, which carries its own endpoint
 * and never reads a connection's URL at all.
 */
const val SLACK_API_URL = "https://slack.com/api"

/** The external services a connection can point at. */
enum class ConnectionType {
    /**
     * Slack: a bot token to call the API with, and optionally an app-level
     * token to open a websocket with.
     *
     * One type rather than two, because there was only ever one thing. The
     * app-level token is not a different kind of connection, it is the extra
     * credential that makes orknux listen as well as send - which is exactly
     * what [SlackListener] has always decided by looking at the token rather
     * than at the type.
     */
    SLACK,
    GITHUB,
    JIRA,
    TEAMS,

    /**
     * A mail server to send through. The only type that is not an HTTP endpoint,
     * so [WorkspaceConnection.url] holds a host name rather than a URL and the
     * settings beside it - port, user, from-address, how the session is secured -
     * are the ones a mail server actually asks for.
     */
    SMTP,

    /** Anything that is just an HTTP endpoint, until it earns a type of its own. */
    WEBHOOK,
}

/**
 * How the connection to a mail server is secured.
 *
 * Two ways, because mail has two: a session that starts in the clear and is
 * upgraded once the server offers it, and one that is encrypted from the first
 * byte. Which one applies is decided by the port the server listens on, not by
 * anything that can be negotiated, so it has to be configured.
 *
 * [NONE] is here for a relay inside a network that does not offer TLS at all -
 * refusing to speak to one would leave those deployments unable to send - and
 * is not what a new connection starts as.
 */
enum class MailSecurity {
    NONE,

    /** Port 587: plain to begin with, encrypted before the credentials are sent. */
    STARTTLS,

    /** Port 465: TLS before anything else, the way HTTPS is. */
    TLS,
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

    /** Encrypted in the database; see [SecretCipher]. */
    @Convert(converter = SecretConverter::class)
    @Column(length = SECRET_COLUMN_LENGTH)
    var secret: String? = null,

    /**
     * A second credential, for services that need one to open a listening
     * socket: Slack's Socket Mode wants an app-level token (`xapp-...`) as well
     * as the bot token that [secret] holds.
     *
     * Optional. A Slack connection without one sends and never listens, which is
     * a whole integration on its own; with one, [SlackListener] opens a socket.
     */
    @Convert(converter = SecretConverter::class)
    @Column(name = "app_token", length = SECRET_COLUMN_LENGTH)
    var appToken: String? = null,

    /**
     * Which port the mail server listens on. Null uses the one that goes with
     * [smtpSecurity], since a workspace that picked STARTTLS almost never means
     * anything other than 587.
     */
    @Column(name = "smtp_port")
    var smtpPort: Int? = null,

    /**
     * Who to log in as. Null sends without authenticating, which is what an
     * internal relay that trusts the network expects; the password is [secret],
     * so a mail credential is encrypted by the same converter as every other.
     */
    @Column(name = "smtp_username", length = 320)
    var smtpUsername: String? = null,

    /**
     * The address the mail is from.
     *
     * Stored rather than taken from [smtpUsername], because the two differ
     * whenever a service account sends as a team - and a provider that refuses a
     * From it has not authorised refuses it on the address, not on the login.
     */
    @Column(name = "smtp_from", length = 320)
    var smtpFrom: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "smtp_security", nullable = false, length = 16)
    var smtpSecurity: MailSecurity = MailSecurity.STARTTLS,

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

    /** Whether anything has been configured to authenticate with. */
    val configured: Boolean
        get() = when (type) {
            /*
             * The bot token alone. [appToken] is what makes the connection
             * listen, not what makes it work, so a Slack connection that only
             * ever posts is configured and must not be reported otherwise.
             */
            ConnectionType.SLACK -> !secret.isNullOrBlank()

            /*
             * A mail server needs somewhere to send from before it needs a
             * password: a relay on an internal network authenticates nobody, and
             * calling that "not configured" would leave it unchecked forever. A
             * user name without a password is the half-filled form it looks like.
             */
            ConnectionType.SMTP ->
                !smtpFrom.isNullOrBlank() && (smtpUsername.isNullOrBlank() || !secret.isNullOrBlank())

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

    /** Encrypted in the database; see [SecretCipher]. */
    @Convert(converter = SecretConverter::class)
    @Column(length = SECRET_COLUMN_LENGTH)
    var secret: String? = null,

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "mcp_server_header", joinColumns = [JoinColumn(name = "mcp_server_id")])
    @OrderColumn(name = "position")
    var headers: MutableList<HttpHeader> = mutableListOf(),
)
