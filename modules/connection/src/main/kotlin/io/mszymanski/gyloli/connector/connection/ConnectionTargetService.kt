package io.mszymanski.gyloli.connector.connection

import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service

/**
 * Resolves a stored connection into something a caller can send a request with.
 *
 * This is what gyloli-workflow asks for when a run has to reach Slack, Jira,
 * GitHub or Teams. It hands over credentials, which is why the service token is
 * the whole of the connector's security and why every call is logged.
 *
 * It is an interim shape. Once the connector has providers of its own, a run
 * will ask it to *do* the thing — post the message, open the issue — and the
 * credentials will stop leaving this service at all. Anything added here should
 * be easy to take away again.
 */
@Service
class ConnectionTargetService(
    private val teamConnections: TeamConnectionRepository,
    private val mcpServers: McpServerRepository,
) {

    fun connectionTarget(teamConnectionId: Long): ConnectionTargetView {
        val connection = teamConnections.findByIdOrNull(teamConnectionId)
            ?: throw ConnectionNotFoundException(teamConnectionId)
        if (!connection.configured) throw ConnectionNotConfiguredException(connection.name)

        log.info("Resolved target for connection {} (team {})", connection.name, connection.teamId)
        return ConnectionTargetView(connection.type, connection.target())
    }

    fun mcpServerTarget(mcpServerId: Long): ConnectionTargetView {
        val server = mcpServers.findByIdOrNull(mcpServerId) ?: throw McpServerNotFoundException(mcpServerId)

        log.info("Resolved target for MCP server {} (team {})", server.name, server.teamId)
        return ConnectionTargetView(null, server.target())
    }

    private companion object {
        val log = LoggerFactory.getLogger(ConnectionTargetService::class.java)
    }
}

data class ConnectionTargetView(
    /** Which service is on the other end; null for an MCP server. */
    val type: ConnectionType?,
    val url: String,
    /** Every header to send, the credential among them. */
    val headers: List<HttpHeaderView>,
) {
    constructor(type: ConnectionType?, target: ConnectionTarget) : this(
        type = type,
        url = target.url,
        headers = target.requestHeaders().map { (name, value) -> HttpHeaderView(name, value) },
    )
}

class ConnectionNotConfiguredException(name: String) :
    RuntimeException("Connection \"$name\" has no credentials configured")
