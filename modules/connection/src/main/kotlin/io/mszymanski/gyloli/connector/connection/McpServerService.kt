package io.mszymanski.gyloli.connector.connection

import org.slf4j.LoggerFactory
import org.springframework.data.domain.Sort
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** The MCP servers a team's agents may connect to. */
@Service
class McpServerService(
    private val servers: McpServerRepository,
) {

    fun mcpServers(teamId: Long): List<McpServerView> =
        servers.findByTeamId(teamId, Sort.by("name")).map(::McpServerView)

    fun mcpServer(id: Long): McpServerView? =
        servers.findByIdOrNull(id)?.let(::McpServerView)

    @Transactional
    fun createMcpServer(input: CreateMcpServerInput): McpServerView {
        val name = input.name.trim()
        val address = input.address.trim()
        if (name.isEmpty()) throw McpServerNameInvalidException()
        if (address.isEmpty()) throw McpServerAddressInvalidException()
        if (servers.findByTeamIdAndName(input.teamId, name) != null) throw McpServerNameTakenException(name)

        val server = servers.save(
            McpServer(
                teamId = input.teamId,
                name = name,
                address = address,
                authType = input.authType ?: AuthType.NONE,
                secret = input.secret?.trim()?.ifEmpty { null },
                headers = input.headers.orEmpty().toHttpHeaders(),
            ),
        )
        return McpServerView(server)
    }

    /** Backs the MCP server settings form; a null secret keeps the stored one. */
    @Transactional
    fun updateMcpServer(id: Long, input: UpdateMcpServerInput): McpServerView {
        val server = servers.findByIdOrNull(id) ?: throw McpServerNotFoundException(id)

        val name = input.name.trim()
        val address = input.address.trim()
        if (name.isEmpty()) throw McpServerNameInvalidException()
        if (address.isEmpty()) throw McpServerAddressInvalidException()
        if (name != server.name && servers.findByTeamIdAndName(server.teamId, name) != null) {
            throw McpServerNameTakenException(name)
        }

        server.name = name
        server.address = address
        input.authType?.let { server.authType = it }
        input.secret?.let { server.secret = it.trim().ifEmpty { null } }
        input.headers?.let { server.headers = it.toHttpHeaders() }
        return McpServerView(server)
    }

    @Transactional
    fun removeMcpServer(id: Long): Boolean {
        val server = servers.findByIdOrNull(id) ?: return false
        servers.delete(server)
        return true
    }

    /** Hands the stored credentials back, for the settings form's "Reveal" action. */
    @Transactional
    fun revealMcpServerSecret(id: Long): String? {
        val server = servers.findByIdOrNull(id) ?: throw McpServerNotFoundException(id)
        log.info("Credentials for MCP server {} (team {}) revealed", server.name, server.teamId)
        return server.secret
    }

    private companion object {
        val log = LoggerFactory.getLogger(McpServerService::class.java)
    }
}

data class CreateMcpServerInput(
    val teamId: Long,
    val name: String,
    val address: String,
    val authType: AuthType? = null,
    val secret: String? = null,
    val headers: List<HttpHeaderInput>? = null,
)

data class UpdateMcpServerInput(
    val name: String,
    val address: String,
    val authType: AuthType? = null,
    /** Null leaves the stored credentials alone; empty clears them. */
    val secret: String? = null,
    val headers: List<HttpHeaderInput>? = null,
)

data class McpServerView(
    val id: Long,
    val teamId: Long,
    val name: String,
    val address: String,
    val authType: AuthType,
    val headers: List<HttpHeaderView>,
    val secretSet: Boolean,
) {
    constructor(server: McpServer) : this(
        id = requireNotNull(server.id),
        teamId = server.teamId,
        name = server.name,
        address = server.address,
        authType = server.authType,
        headers = server.headers.map { HttpHeaderView(it.name, it.value) },
        secretSet = !server.secret.isNullOrBlank(),
    )
}

class McpServerNotFoundException(id: Long) : RuntimeException("No MCP server with id $id")

class McpServerNameTakenException(name: String) :
    RuntimeException("An MCP server named \"$name\" already exists in this team")

class McpServerNameInvalidException : RuntimeException("An MCP server name is required")

class McpServerAddressInvalidException : RuntimeException("An MCP server address is required")
