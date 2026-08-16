package io.mszymanski.orknux.server.agent

import io.mszymanski.orknux.connector.connection.McpClient
import io.mszymanski.orknux.connector.connection.McpListing
import io.mszymanski.orknux.connector.connection.McpServer
import io.mszymanski.orknux.connector.connection.McpServerRepository
import io.mszymanski.orknux.connector.model.ToolParameterSpec
import io.mszymanski.orknux.connector.model.ToolSpec
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service

/**
 * The tools an agent's MCP servers offer.
 *
 * Names are prefixed with the server — `brave-search__web_search` — because two
 * servers offering `search` is the ordinary case rather than the exceptional
 * one, and a call whose destination depends on which server was listed first is
 * not something anybody could debug.
 *
 * Listing asks the server, every time. A cache here would be a cache of what a
 * remote system can do, invalidated by events this process never sees; asking is
 * a round trip and being right is worth one.
 *
 * A server that cannot be reached contributes nothing rather than failing the
 * conversation. An agent whose search server is down should still be able to
 * answer from what it knows, and it will be told the tool is missing if it tries.
 */
@Service
class McpToolCaller(
    private val servers: McpServerRepository,
    private val client: McpClient,
) {

    /** The servers this agent was granted, as the workspace names them. */
    fun granted(agent: Agent): List<McpServer> {
        if (agent.mcpServers.isEmpty()) return emptyList()
        val held = agent.mcpServers.toSet()
        return servers.findByWorkspaceId(agent.workspaceId, Sort.by("name")).filter { it.name in held }
    }

    fun specsFor(agent: Agent): List<ToolSpec> = granted(agent).flatMap { server ->
        when (val listing = client.tools(server)) {
            is McpListing.Failed -> {
                log.warn("MCP server {} could not be listed: {}", server.name, listing.reason)
                emptyList()
            }

            is McpListing.Tools -> listing.tools.map { tool ->
                ToolSpec(
                    name = qualified(server, tool.name),
                    description = tool.description.ifBlank { "A tool offered by ${server.name}." },
                    parameters = tool.parameters.map {
                        ToolParameterSpec(it.name, it.description, it.required)
                    },
                )
            }
        }
    }

    /** Whether this name belongs to one of the agent's servers, and which. */
    fun resolve(agent: Agent, name: String): Pair<McpServer, String>? = granted(agent)
        .firstOrNull { name.startsWith(prefix(it)) }
        ?.let { server -> server to name.removePrefix(prefix(server)) }

    fun call(server: McpServer, tool: String, arguments: String): String = client.call(server, tool, arguments)

    /**
     * `server__tool`, with anything a model cannot reliably send stripped out of
     * the server's name — both request shapes want a plain identifier.
     */
    private fun qualified(server: McpServer, tool: String): String = prefix(server) + tool

    private fun prefix(server: McpServer): String =
        server.name.lowercase().replace(UNSAFE, "_").trim('_') + "__"

    private companion object {
        val UNSAFE = Regex("[^a-z0-9_-]+")
        val log = LoggerFactory.getLogger(McpToolCaller::class.java)
    }
}
