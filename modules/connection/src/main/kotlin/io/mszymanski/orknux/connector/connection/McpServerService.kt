package io.mszymanski.orknux.connector.connection

import io.mszymanski.orknux.connector.security.HeldSecret
import io.mszymanski.orknux.connector.security.SecretReferences
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Sort
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** The MCP servers a workspace's agents may connect to. */
@Service
class McpServerService(
    private val servers: McpServerRepository,
    /**
     * The rule a secret field follows when it may keep its own value or read a
     * workspace one - see [SecretReferences].
     */
    private val references: SecretReferences,
) {

    fun mcpServers(workspaceId: Long): List<McpServerView> =
        servers.findByWorkspaceId(workspaceId, Sort.by("name")).map(::view)

    fun mcpServer(id: Long): McpServerView? =
        servers.findByIdOrNull(id)?.let(::view)

    /**
     * The MCP servers in this workspace reading [variableId], by name.
     *
     * What `VariableAPI` asks before it removes a variable or takes its secrecy
     * away. Names rather than rows: the answer is a sentence somebody reads.
     */
    fun serversReading(workspaceId: Long, variableId: Long): List<String> =
        servers.findByWorkspaceIdAndSecretVariableId(workspaceId, variableId).map { it.name }.sorted()

    @Transactional
    fun createMcpServer(input: CreateMcpServerInput): McpServerView {
        val name = input.name.trim()
        val address = input.address.trim()
        if (name.isEmpty()) throw McpServerNameInvalidException()
        if (address.isEmpty()) throw McpServerAddressInvalidException()
        if (servers.findByWorkspaceIdAndName(input.workspaceId, name) != null) throw McpServerNameTakenException(name)

        val own = input.secret?.trim()?.ifEmpty { null }
        val variable = references.bind(input.workspaceId, input.secretVariableId, own)

        val server = servers.save(
            McpServer(
                workspaceId = input.workspaceId,
                name = name,
                address = address,
                authType = input.authType ?: AuthType.NONE,
                secret = if (variable == null) own else null,
                secretVariableId = variable,
                headers = input.headers.orEmpty().toHttpHeaders(),
            ),
        )
        return view(server)
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

        // A value given keeps a copy and drops any reference; a variable given
        // reads it and drops any copy; nothing given leaves the field alone,
        // which is what a masked box sends.
        val own = input.secret?.trim()
        val variable = references.bind(server.workspaceId, input.secretVariableId, own?.ifEmpty { null })
        when {
            variable != null -> {
                server.secretVariableId = variable
                server.secret = null
            }

            own != null -> {
                server.secret = own.ifEmpty { null }
                server.secretVariableId = null
            }
        }
        input.headers?.let { server.headers = it.toHttpHeaders() }
        return view(server)
    }

    @Transactional
    fun removeMcpServer(id: Long): Boolean {
        val server = servers.findByIdOrNull(id) ?: return false
        servers.delete(server)
        return true
    }

    /**
     * Hands the stored credentials back, for the settings form's "Reveal" action.
     *
     * A server reading a variable reveals nothing here: revealing a secret is
     * recorded against the secret, and a second door onto the same value under
     * this name would be a reveal nobody could find in the log.
     */
    @Transactional
    fun revealMcpServerSecret(id: Long): String? {
        val server = servers.findByIdOrNull(id) ?: throw McpServerNotFoundException(id)
        if (server.secretVariableId != null) return null
        log.info("Credentials for MCP server {} (workspace {}) revealed", server.name, server.workspaceId)
        return server.secret
    }

    /** A server as a screen sees it, with the variable it reads named. */
    private fun view(server: McpServer) =
        McpServerView(server, references.describe(server.workspaceId, server.secretVariableId))

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
    /** A workspace secret to read the credential from instead of keeping a copy. */
    val secretVariableId: Long? = null,
    val headers: List<HttpHeaderInput>? = null,
)

data class UpdateMcpServerInput(
    val name: String,
    val address: String,
    val authType: AuthType? = null,
    /** Null leaves the stored credential alone; empty clears it, reference and all. */
    val secret: String? = null,
    /**
     * Points the credential at a workspace secret, dropping any copy it held.
     * Null leaves it as it is; sending it with [secret] is refused.
     */
    val secretVariableId: Long? = null,
    val headers: List<HttpHeaderInput>? = null,
)

data class McpServerView(
    val id: Long,
    val workspaceId: Long,
    val name: String,
    val address: String,
    val authType: AuthType,
    val headers: List<HttpHeaderView>,
    /** Whether the server holds a credential of its own. False for one reading a variable. */
    val secretSet: Boolean,
    /** The workspace secret it reads instead, or null when it keeps its own copy. */
    val secretVariableId: Long?,
    /** What that variable is called, and which catalog holds it. */
    val secretVariableName: String?,
    val secretVariableCatalog: String?,
    /** A reference pointing at nothing, reported rather than assumed away. */
    val secretVariableMissing: Boolean,
) {
    constructor(server: McpServer, held: HeldSecret? = null) : this(
        id = requireNotNull(server.id),
        workspaceId = server.workspaceId,
        name = server.name,
        address = server.address,
        authType = server.authType,
        headers = server.headers.map { HttpHeaderView(it.name, it.value) },
        secretSet = !server.secret.isNullOrBlank(),
        secretVariableId = server.secretVariableId,
        secretVariableName = held?.name,
        secretVariableCatalog = held?.catalog,
        secretVariableMissing = server.secretVariableId != null && held == null,
    )
}

class McpServerNotFoundException(id: Long) : RuntimeException("No MCP server with id $id")

class McpServerNameTakenException(name: String) :
    RuntimeException("An MCP server named \"$name\" already exists in this workspace")

class McpServerNameInvalidException : RuntimeException("An MCP server name is required")

class McpServerAddressInvalidException : RuntimeException("An MCP server address is required")
