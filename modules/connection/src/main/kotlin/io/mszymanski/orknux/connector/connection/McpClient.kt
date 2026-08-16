package io.mszymanski.orknux.connector.connection

import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** One tool an MCP server offers, as it describes itself. */
data class McpTool(
    val name: String,
    val description: String,
    /** Parameter names from the tool's own input schema, required ones first. */
    val parameters: List<McpParameter>,
)

data class McpParameter(val name: String, val description: String, val required: Boolean)

/** What a server said it can do, or why it could not be asked. */
sealed interface McpListing {
    data class Tools(val tools: List<McpTool>) : McpListing
    data class Failed(val reason: String) : McpListing
}

@ConfigurationProperties(prefix = "orknux.mcp")
data class McpProperties(
    /** How long a server has to answer before the call is given up on. */
    val timeout: Duration = Duration.ofSeconds(30),
)

/**
 * Talks to an MCP server over Streamable HTTP.
 *
 * Here rather than in the app for the reason everything outbound is: it needs
 * the credential, and credentials are resolved in one place — [McpServer.target]
 * builds the headers, the same as for a connection check.
 *
 * Every call opens its own session: initialize, then the request. That is one
 * extra round trip per call, and it is deliberate — a pooled session would have
 * to survive a server restart, a token expiring, and two agents using one server
 * at once, and none of that is worth carrying until somebody is running enough
 * calls to notice. The cost is a request; the alternative is a cache with three
 * invalidation rules.
 *
 * Nothing here trusts the server. A tool with no name is dropped, a description
 * that is missing becomes an empty one, and a result that is not what the
 * protocol describes is reported as a failure rather than passed to a model as
 * though it were an answer.
 */
@Service
@EnableConfigurationProperties(McpProperties::class)
class McpClient(
    private val mapper: ObjectMapper,
    private val properties: McpProperties,
) {

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    /** What this server offers, or why it could not say. */
    fun tools(server: McpServer): McpListing {
        val session = open(server) ?: return McpListing.Failed("The server did not complete the MCP handshake")
        val answer = send(server, session, "tools/list", mapper.createObjectNode(), id = 2)
            ?: return McpListing.Failed("The server did not answer tools/list")

        answer.path("error").takeIf { !it.isMissingNode }?.let { error ->
            return McpListing.Failed(error.path("message").stringValue() ?: "The server refused tools/list")
        }

        val listed = answer.path("result").path("tools") as? ArrayNode
            ?: return McpListing.Failed("The server answered tools/list without any tools")
        return McpListing.Tools(listed.mapNotNull(::toolOf))
    }

    /**
     * Calls one, and hands back what it said as JSON text.
     *
     * A failure comes back as a result rather than an exception, the way a
     * workspace tool's does: the model can be told the lookup failed and carry
     * on, which beats the conversation dying because a server was down.
     */
    fun call(server: McpServer, tool: String, arguments: String): String {
        val session = open(server)
            ?: return failure("${server.name} did not complete the MCP handshake")

        val params = mapper.createObjectNode()
        params.put("name", tool)
        params.set("arguments", argumentsOf(arguments))

        val answer = send(server, session, "tools/call", params, id = 3)
            ?: return failure("${server.name} did not answer")

        answer.path("error").takeIf { !it.isMissingNode }?.let { error ->
            return failure(error.path("message").stringValue() ?: "${server.name} refused the call")
        }

        // The protocol returns content blocks; the text ones are what a model
        // can read. Anything else is described rather than dropped silently.
        val blocks = answer.path("result").path("content") as? ArrayNode
            ?: return mapper.writeValueAsString(mapOf("result" to answer.path("result")))
        val text = blocks.joinToString("\n") { block ->
            when (block.path("type").stringValue()) {
                "text" -> block.path("text").stringValue().orEmpty()
                else -> "[${block.path("type").stringValue() ?: "unknown"} content, which cannot be read as text]"
            }
        }
        return mapper.writeValueAsString(mapOf("result" to text))
    }

    /**
     * Initializes a session and returns its id, or null when the server would
     * not start one. A server that returns no session id is still usable —
     * the header is optional — so an empty string stands for "no session".
     */
    private fun open(server: McpServer): String? {
        val params = mapper.createObjectNode()
        params.put("protocolVersion", PROTOCOL_VERSION)
        params.putObject("capabilities")
        params.putObject("clientInfo").put("name", CLIENT_NAME).put("version", CLIENT_VERSION)

        val response = post(server, session = null, body = request("initialize", params, id = 1)) ?: return null
        if (response.statusCode() !in 200..299) {
            log.warn("MCP server {} answered {} to initialize", server.name, response.statusCode())
            return null
        }

        val session = response.headers().firstValue(SESSION_HEADER).orElse("")
        // The notification that the handshake is done. It has no reply, and a
        // server that ignores it is not a server that is broken.
        runCatching { post(server, session, request("notifications/initialized", mapper.createObjectNode(), id = null)) }
        return session
    }

    private fun send(server: McpServer, session: String, method: String, params: ObjectNode, id: Int): JsonNode? {
        val response = post(server, session, request(method, params, id)) ?: return null
        if (response.statusCode() !in 200..299) {
            log.warn("MCP server {} answered {} to {}", server.name, response.statusCode(), method)
            return null
        }
        return parse(response.body())
    }

    private fun post(server: McpServer, session: String?, body: String): HttpResponse<String>? = try {
        val target = server.target()
        val builder = HttpRequest.newBuilder(URI(server.address))
            .timeout(properties.timeout)
            .header("Content-Type", "application/json")
            // Either shape is acceptable; a server may answer with a stream.
            .header("Accept", "application/json, text/event-stream")
        target.requestHeaders().forEach { (name, value) -> builder.header(name, value) }
        session?.takeIf { it.isNotEmpty() }?.let { builder.header(SESSION_HEADER, it) }

        client.send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())
    } catch (failure: Exception) {
        log.warn("Could not reach MCP server {}", server.name, failure)
        null
    }

    /**
     * The body, whether it arrived as JSON or as one server-sent event.
     *
     * Streamable HTTP allows either, and which one a server picks is not
     * something the caller should have to care about.
     */
    private fun parse(body: String): JsonNode? = runCatching {
        val payload = if (body.trimStart().startsWith("{")) {
            body
        } else {
            body.lineSequence()
                .filter { it.startsWith("data:") }
                .map { it.removePrefix("data:").trim() }
                .lastOrNull { it.isNotEmpty() }
                ?: return null
        }
        mapper.readTree(payload)
    }.getOrNull()

    private fun request(method: String, params: ObjectNode, id: Int?): String {
        val root = mapper.createObjectNode()
        root.put("jsonrpc", "2.0")
        id?.let { root.put("id", it) }
        root.put("method", method)
        root.set("params", params)
        return mapper.writeValueAsString(root)
    }

    /** A tool the server described well enough to offer; null when it did not. */
    private fun toolOf(node: JsonNode): McpTool? {
        val name = node.path("name").stringValue()?.takeIf { it.isNotBlank() } ?: return null
        val schema = node.path("inputSchema")
        val required = (schema.path("required") as? ArrayNode)
            ?.mapNotNull { it.stringValue() }
            .orEmpty()
            .toSet()

        val properties = schema.path("properties")
        val parameters = properties.propertyNames().map { parameter ->
            McpParameter(
                name = parameter,
                description = properties.path(parameter).path("description").stringValue().orEmpty(),
                required = parameter in required,
            )
        }
        return McpTool(
            name = name,
            description = node.path("description").stringValue().orEmpty(),
            parameters = parameters.sortedByDescending { it.required },
        )
    }

    private fun argumentsOf(arguments: String): ObjectNode =
        runCatching { mapper.readTree(arguments) as? ObjectNode }.getOrNull() ?: mapper.createObjectNode()

    private fun failure(reason: String): String = mapper.writeValueAsString(mapOf("error" to reason))

    private companion object {
        /** The revision of the protocol this speaks. */
        const val PROTOCOL_VERSION = "2025-06-18"
        const val SESSION_HEADER = "Mcp-Session-Id"
        const val CLIENT_NAME = "ordilumen"
        const val CLIENT_VERSION = "1.0"
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(10)

        val log = LoggerFactory.getLogger(McpClient::class.java)
    }
}
