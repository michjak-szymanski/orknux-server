package io.mszymanski.orknux.connector.connection

import io.mszymanski.orknux.connector.security.HeldCredential
import io.mszymanski.orknux.connector.security.SecretReferences
import org.springframework.stereotype.Component

/**
 * What a stored connection authenticates with, read in one place.
 *
 * It used to be read off the entity: `connection.secret` at six call sites, each
 * one a place that would have to learn the new rule. There is a new rule - a
 * secret field either keeps its own copy or reads a workspace variable, per
 * field - and a credential that resolves differently depending on which of the
 * six asked would be the worst possible outcome of adding it. So the entity's
 * columns are the storage and this is the reading, and the extension functions
 * that used to hand a target out without a bean to hold are gone rather than
 * left as a second door.
 *
 * Every method answers with a [HeldCredential] or with something built from one,
 * so a caller that wants to explain itself can say which of the four ways there
 * was nothing to send, and a caller that only wants the value asks for
 * `.credential`.
 */
@Component
class ConnectionCredentials(private val references: SecretReferences) {

    /** The bot token, API credential or mail password this connection holds. */
    fun secretOf(connection: WorkspaceConnection): HeldCredential =
        references.read(connection.workspaceId, connection.secret, connection.secretVariableId)

    /** Slack's app-level token, which chooses its source separately from the bot token. */
    fun appTokenOf(connection: WorkspaceConnection): HeldCredential =
        references.read(connection.workspaceId, connection.appToken, connection.appTokenVariableId)

    fun secretOf(server: McpServer): HeldCredential =
        references.read(server.workspaceId, server.secret, server.secretVariableId)

    /**
     * Everything needed to send one HTTP request through this connection.
     *
     * A credential that cannot be produced is left out rather than substituted:
     * the request then goes unauthenticated and comes back a 401, which is what
     * used to happen with an unreadable envelope and is a good deal better than
     * putting `orkx1:…` in an Authorization header.
     */
    fun target(connection: WorkspaceConnection): ConnectionTarget = ConnectionTarget(
        url = connection.effectiveUrl,
        authType = connection.authType,
        secret = secretOf(connection).credential,
        headers = connection.headers.toList(),
    )

    fun target(server: McpServer): ConnectionTarget = ConnectionTarget(
        url = server.address,
        authType = server.authType,
        secret = secretOf(server).credential,
        headers = server.headers.toList(),
    )

    /**
     * The mail server one connection points at, or null when it does not point
     * at one yet.
     *
     * The counterpart of [target] for the type that is not an HTTP endpoint, so
     * nothing above this module handles a mail password.
     */
    fun smtpServer(connection: WorkspaceConnection): SmtpServer? {
        if (connection.type != ConnectionType.SMTP) return null
        val host = connection.effectiveUrl.trim().ifEmpty { return null }
        val from = connection.smtpFrom?.trim()?.ifEmpty { null } ?: return null
        val user = connection.smtpUsername?.trim()?.ifEmpty { null }

        return SmtpServer(
            host = host,
            port = connection.smtpPort ?: connection.smtpSecurity.defaultPort,
            username = user,
            // Only meaningful with a user to go with it; a password on its own is
            // something left behind by an earlier configuration.
            password = user?.let { secretOf(connection).credential },
            from = from,
            security = connection.smtpSecurity,
        )
    }
}
