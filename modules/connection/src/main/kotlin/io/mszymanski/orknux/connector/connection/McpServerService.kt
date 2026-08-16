package io.mszymanski.orknux.connector.connection

import org.slf4j.LoggerFactory
import org.springframework.data.domain.Sort
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** The MCP servers a workspace's agents may connect to. */
@Service
class McpServerService(
    private val servers: McpServerRepository,
) {

    fun mcpServers(workspaceId: Long): List<McpServerView> =
        servers.findByWorkspaceId(workspaceId, Sort.by("name")).map(::McpServerView)

    fun mcpServer(id: Long): McpServerView? =
        servers.findByIdOrNull(id)?.let(::McpServerView)

    @Transactional
    fun createMcpServer(input: CreateMcpServerInput): McpServerView {
        val name = input.name.trim()
        val address = input.address.trim()
        if (name.isEmpty()) throw McpServerNameInvalidException()
        if (address.isEmpty()) throw McpServerAddressInvalidException()
        if (servers.findByWorkspaceIdAndName(input.workspaceId, name) != null) throw McpServerNameTakenException(name)

        val server = servers.save(
            McpServer(
                workspaceId = input.workspaceId,
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
        if (name != server.name && servers.findByWorkspaceIdAndName(server.workspaceId, name) != null) {
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
        log.info("Credentials for MCP server {} (workspace {}) revealed", server.name, server.workspaceId)
        return server.secret
    }

    private companion object {
        val log = LoggerFactory.getLogger(McpServerService::class.java)
    }
}

data class CreateMcpServerInput(
    val workspaceId: Long,
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
    val workspaceId: Long,
    val name: String,
    val address: String,
    val authType: AuthType,
    val headers: List<HttpHeaderView>,
    val secretSet: Boolean,
) {
    constructor(server: McpServer) : this(
        id = requireNotNull(server.id),
        workspaceId = server.workspaceId,
        name = server.name,
        address = server.address,
        authType = server.authType,
        headers = server.headers.map { HttpHeaderView(it.name, it.value) },
        secretSet = !server.secret.isNullOrBlank(),
    )
}

class McpServerNotFoundException(id: Long) : RuntimeException("No MCP server with id $id")

class McpServerNameTakenException(name: String) :
    RuntimeException("An MCP server named \"$name\" already exists in this workspace")

class McpServerNameInvalidException : RuntimeException("An MCP server name is required")

class McpServerAddressInvalidException : RuntimeException("An MCP server address is required")
