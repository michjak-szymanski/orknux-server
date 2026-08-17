package io.mszymanski.orknux.connector.model

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode

/**
 * One turn of a conversation, as the caller has it. Framework-free on purpose.
 *
 * A turn is usually text. Two kinds are not: an assistant turn that asked for
 * tools carries [asked] and no content worth showing, and the turn answering one
 * carries [respondingTo] — the id of the call it answers. Both shapes need those
 * threaded back on the next request or the model cannot match its own question
 * to the answer.
 */
data class ChatTurn(
    val role: String,
    val content: String,
    /**
     * Pictures sent with this turn, as `data:` URLs.
     *
     * Carried beside the text rather than described in it, because a model that
     * can see takes them as part of the message: the OpenAI shape is a content
     * array of `text` and `image_url` parts, which llama.cpp, vLLM and OpenAI
     * itself all read. Empty for every turn that is only words, which keeps the
     * request the plain string shape everything understands.
     */
    val images: List<String> = emptyList(),
    /** Set on an assistant turn that asked for tools. */
    val asked: List<ToolCall> = emptyList(),
    /** Set on a turn answering one, naming the call it answers. */
    val respondingTo: String? = null,
)

/** A model asking for a tool, with the arguments it chose, as JSON. */
data class ToolCall(val id: String, val name: String, val arguments: String)

/**
 * What a tool looks like to a model deciding whether to call it.
 *
 * Declared here rather than taken from the app, because this is the layer that
 * turns it into whichever request shape the provider wants. The schema is
 * deliberately small — a name, a description, and named string parameters —
 * because everything crossing this boundary is JSON text anyway.
 */
data class ToolSpec(
    val name: String,
    val description: String,
    val parameters: List<ToolParameterSpec> = emptyList(),
)

data class ToolParameterSpec(val name: String, val description: String, val required: Boolean = false)

/** What a model answered, or why it did not. */
sealed interface ChatCompletion {
    data class Answered(
        val content: String,
        val millis: Long,
        /** What the provider said it charged for; zero when it said nothing. */
        val inputTokens: Long = 0,
        val outputTokens: Long = 0,
    ) : ChatCompletion

    /**
     * The model wants tools run before it will answer.
     *
     * Its own turn is handed back with it, so the caller can put the question
     * and the answers into the next request without reconstructing either.
     */
    data class CalledTools(
        val calls: List<ToolCall>,
        val turn: ChatTurn,
        val millis: Long,
        val inputTokens: Long = 0,
        val outputTokens: Long = 0,
    ) : ChatCompletion

    data class Failed(val reason: String) : ChatCompletion
}

@ConfigurationProperties(prefix = "orknux.model")
data class ModelChatProperties(
    /** How long a model has to answer before the request is given up on. */
    val timeout: Duration = Duration.ofMinutes(2),
)

/**
 * Calls a model and returns what it said.
 *
 * This lives here for the same reason the probe does: it needs the credential,
 * and credentials are read in one place. The caller hands over turns and a model
 * id and gets text back — it never sees a key, and it never learns which shape
 * of request the provider wanted.
 *
 * Two shapes are spoken. Anthropic has its own; everything else here speaks the
 * OpenAI chat-completions shape, which Azure OpenAI, Ollama and most
 * self-hosted servers also answer.
 */
@Service
@EnableConfigurationProperties(ModelChatProperties::class)
class ModelChatClient(
    private val providers: ModelProviderRepository,
    private val models: LlmModelRepository,
    private val probe: ModelProviderProbe,
    private val mapper: ObjectMapper,
    private val properties: ModelChatProperties,
    private val usage: ModelUsageRecorder,
) {

    /**
     * Every answered call is counted, wherever it came from.
     *
     * Here rather than in the callers because this is the only thing that calls
     * a model: a second caller added later would otherwise have to remember,
     * and the metrics would quietly under-report.
     */
    private fun counted(modelId: Long, answer: ChatCompletion): ChatCompletion {
        // A round that only asked for tools still cost tokens and still took
        // time, so it counts: an agent's real cost is every round it took.
        when (answer) {
            is ChatCompletion.Answered ->
                runCatching { usage.record(modelId, answer.inputTokens, answer.outputTokens, answer.millis) }
            is ChatCompletion.CalledTools ->
                runCatching { usage.record(modelId, answer.inputTokens, answer.outputTokens, answer.millis) }
            is ChatCompletion.Failed -> Unit
        }
        return answer
    }

    /**
     * What the provider said the call cost. OpenAI reports
     * `usage.prompt_tokens` / `completion_tokens`; Anthropic reports
     * `usage.input_tokens` / `output_tokens`. A provider that reports neither
     * leaves them zero rather than being guessed at.
     */
    private fun tokensOf(body: String): Pair<Long, Long> = runCatching {
        val usageNode = mapper.readTree(body).path("usage")
        val input = usageNode.path("prompt_tokens").asLong(usageNode.path("input_tokens").asLong(0))
        val output = usageNode.path("completion_tokens").asLong(usageNode.path("output_tokens").asLong(0))
        input to output
    }.getOrDefault(0L to 0L)

    private val client: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(CONNECT_SECONDS))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    /**
     * Calls a model and hands back what it says as it says it.
     *
     * The same request as [complete] with `stream` set, read frame by frame:
     * each piece goes to [onChunk] as it lands and is also accumulated, so the
     * caller gets the whole answer at the end and does not have to reassemble
     * it. A model that takes a minute to think is the reason this exists —
     * without it the screen is blank for that minute.
     *
     * [onChunk] runs on the calling thread, between reads.
     */
    fun stream(modelId: Long, turns: List<ChatTurn>, onChunk: (String) -> Unit): ChatCompletion {
        val call = prepare(modelId, turns, streaming = true)
        if (call is Prepared.Failed) return ChatCompletion.Failed(call.reason)
        val ready = call as Prepared.Call

        val started = System.nanoTime()
        return try {
            val response = client.send(ready.request, HttpResponse.BodyHandlers.ofInputStream())
            if (response.statusCode() !in 200..299) {
                val body = response.body().reader().use { it.readText() }
                return ChatCompletion.Failed(reason(response.statusCode(), body))
            }

            val whole = StringBuilder()
            var input = 0L
            var output = 0L
            response.body().bufferedReader().use { reader ->
                reader.lineSequence().forEach { line ->
                    // The counts come in a frame of their own, usually the last
                    // one, and carry no text — so they are read from every frame
                    // rather than from the answer.
                    if (line.startsWith(DATA_PREFIX)) {
                        val (frameInput, frameOutput) = tokensOf(line.removePrefix(DATA_PREFIX).trim())
                        if (frameInput > 0) input = frameInput
                        if (frameOutput > 0) output = frameOutput
                    }
                    val piece = chunkOf(line, ready.anthropic) ?: return@forEach
                    whole.append(piece)
                    onChunk(piece)
                }
            }
            val millis = (System.nanoTime() - started) / 1_000_000
            if (whole.isEmpty()) {
                ChatCompletion.Failed("The provider answered with no message")
            } else {
                counted(modelId, ChatCompletion.Answered(whole.toString(), millis, input, output))
            }
        } catch (failure: Exception) {
            ChatCompletion.Failed(failure.message ?: "The provider could not be reached")
        }
    }

    /**
     * One piece of an answer out of one line of the stream, or null when the
     * line carries none — a keep-alive, a blank, an event name, or the `[DONE]`
     * both shapes end with.
     *
     * Anthropic sends the text on `content_block_delta` and other things on
     * other events, so the field is read rather than the event name: a delta
     * with no `text` is a delta about something else.
     */
    private fun chunkOf(line: String, anthropic: Boolean): String? {
        if (!line.startsWith(DATA_PREFIX)) return null
        val payload = line.removePrefix(DATA_PREFIX).trim()
        if (payload.isEmpty() || payload == DONE) return null

        return runCatching {
            val tree = mapper.readTree(payload)
            if (anthropic) {
                tree.path("delta").path("text").stringValue()
            } else {
                tree.path("choices").firstOrNull()?.path("delta")?.path("content")?.stringValue()
            }
        }.getOrNull()?.ifEmpty { null }
    }

    /**
     * @param tools what the model may call. Empty means it answers or fails —
     *   which is every caller that is not running an agent.
     */
    fun complete(modelId: Long, turns: List<ChatTurn>, tools: List<ToolSpec> = emptyList()): ChatCompletion {
        val call = prepare(modelId, turns, streaming = false, tools = tools)
        if (call is Prepared.Failed) return ChatCompletion.Failed(call.reason)
        val ready = call as Prepared.Call

        val started = System.nanoTime()
        return try {
            val response = client.send(ready.request, HttpResponse.BodyHandlers.ofString())
            val millis = (System.nanoTime() - started) / 1_000_000

            if (response.statusCode() !in 200..299) {
                return ChatCompletion.Failed(reason(response.statusCode(), response.body()))
            }
            val (input, output) = tokensOf(response.body())

            // A model that asked for tools has not answered yet, and its text —
            // if it sent any — is thinking aloud rather than a reply.
            val asked = if (ready.anthropic) anthropicCalls(response.body()) else openAiCalls(response.body())
            if (asked.isNotEmpty()) {
                val said = (if (ready.anthropic) anthropicContent(response.body()) else openAiContent(response.body()))
                    .orEmpty()
                return counted(
                    modelId,
                    ChatCompletion.CalledTools(
                        calls = asked,
                        turn = ChatTurn("assistant", said, asked = asked),
                        millis = millis,
                        inputTokens = input,
                        outputTokens = output,
                    ),
                )
            }

            val content = (if (ready.anthropic) anthropicContent(response.body()) else openAiContent(response.body()))
                ?: return ChatCompletion.Failed("The provider answered with no message")
            counted(modelId, ChatCompletion.Answered(content, millis, input, output))
        } catch (failure: Exception) {
            ChatCompletion.Failed(failure.message ?: "The provider could not be reached")
        }
    }

    /**
     * Everything needed to make the call, or why it cannot be made.
     *
     * Both paths resolve the same model, the same credential and the same body;
     * only what they do with the response differs. Building it twice is how the
     * two drift apart.
     */
    private fun prepare(
        modelId: Long,
        turns: List<ChatTurn>,
        streaming: Boolean,
        tools: List<ToolSpec> = emptyList(),
    ): Prepared {
        val model = models.findByIdOrNull(modelId)
            ?: return Prepared.Failed("That model no longer exists")
        if (!model.enabled) return Prepared.Failed("${model.name} is not active")

        val provider = providers.findByIdOrNull(model.providerId)
            ?: return Prepared.Failed("The provider ${model.name} belongs to no longer exists")

        val credential = when (val resolved = probe.credentials(provider)) {
            is ModelProviderProbe.Credential.Failed -> return Prepared.Failed(resolved.reason)
            is ModelProviderProbe.Credential.Header -> resolved.header
        }

        val anthropic = provider.type == ProviderType.ANTHROPIC
        val uri = try {
            URI(endpointFor(provider, model, anthropic))
        } catch (_: Exception) {
            return Prepared.Failed("The provider endpoint is not a usable URL")
        }

        val body = if (anthropic) {
            anthropicBody(model, turns, streaming, tools)
        } else {
            openAiBody(model, turns, streaming, tools)
        }
        val builder = HttpRequest.newBuilder(uri)
            .timeout(properties.timeout)
            .header("Content-Type", "application/json")
            .header(credential.name, credential.value)
        // Anthropic wants its version pinned on every request.
        if (anthropic) builder.header("anthropic-version", ANTHROPIC_VERSION)

        return Prepared.Call(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(), anthropic)
    }

    private sealed interface Prepared {
        data class Call(val request: HttpRequest, val anthropic: Boolean) : Prepared
        data class Failed(val reason: String) : Prepared
    }

    /**
     * Where the request goes. Azure OpenAI puts the deployment and the API
     * version in the path rather than in the body, which is the whole of its
     * difference from the shape everything else uses.
     */
    private fun endpointFor(provider: ModelProvider, model: LlmModel, anthropic: Boolean): String {
        val base = provider.endpoint.trimEnd('/')
        return when {
            anthropic -> "$base/messages"
            provider.type == ProviderType.AZURE_OPENAI -> {
                val deployment = provider.deploymentName?.ifBlank { null } ?: model.modelId
                val version = provider.apiVersion?.ifBlank { null } ?: DEFAULT_AZURE_VERSION
                "$base/openai/deployments/$deployment/chat/completions?api-version=$version"
            }
            else -> "$base/chat/completions"
        }
    }

    private fun openAiBody(
        model: LlmModel,
        turns: List<ChatTurn>,
        streaming: Boolean,
        tools: List<ToolSpec> = emptyList(),
    ): String {
        val root = mapper.createObjectNode()
        root.put("model", model.modelId)
        if (streaming) root.put("stream", true)
        model.maxOutput?.let { root.put("max_tokens", it) }
        val messages = root.putArray("messages")
        turns.forEach { turn ->
            val message = messages.addObject()
            when {
                // The answer to a tool call is its own role, and has to name the
                // call it answers or the model cannot pair them up.
                turn.respondingTo != null -> message
                    .put("role", "tool")
                    .put("tool_call_id", turn.respondingTo)
                    .put("content", turn.content)

                turn.asked.isNotEmpty() -> {
                    message.put("role", turn.role)
                    // A turn that only asked may carry no text at all.
                    if (turn.content.isNotEmpty()) message.put("content", turn.content)
                    val calls = message.putArray("tool_calls")
                    turn.asked.forEach { asked ->
                        val call = calls.addObject()
                        call.put("id", asked.id).put("type", "function")
                        call.putObject("function").put("name", asked.name).put("arguments", asked.arguments)
                    }
                }

                /*
                 * A turn with pictures is a list of parts rather than a string.
                 *
                 * `[{type: text}, {type: image_url}]` is the shape OpenAI
                 * defined and llama.cpp, vLLM and Ollama all read; a model that
                 * cannot see ignores the image part rather than failing, which
                 * is why this does not need to know whether the model can.
                 */
                turn.images.isNotEmpty() -> {
                    message.put("role", turn.role)
                    val parts = message.putArray("content")
                    if (turn.content.isNotEmpty()) {
                        parts.addObject().put("type", "text").put("text", turn.content)
                    }
                    turn.images.forEach { image ->
                        parts.addObject()
                            .put("type", "image_url")
                            .putObject("image_url")
                            .put("url", image)
                    }
                }

                else -> message.put("role", turn.role).put("content", turn.content)
            }
        }

        if (tools.isNotEmpty()) declareOpenAiTools(root, tools)
        return mapper.writeValueAsString(root)
    }

    private fun declareOpenAiTools(root: ObjectNode, tools: List<ToolSpec>) {
        val declared = root.putArray("tools")
        tools.forEach { tool ->
            val entry = declared.addObject()
            entry.put("type", "function")
            val function = entry.putObject("function")
            function.put("name", tool.name).put("description", tool.description)
            val schema = function.putObject("parameters")
            schema.put("type", "object")
            val properties = schema.putObject("properties")
            tool.parameters.forEach { parameter ->
                properties.putObject(parameter.name).put("type", "string").put("description", parameter.description)
            }
            val required = schema.putArray("required")
            tool.parameters.filter { it.required }.forEach { required.add(it.name) }
        }
    }

    /** Anthropic takes the system turn beside the messages rather than among them. */
    private fun anthropicBody(
        model: LlmModel,
        turns: List<ChatTurn>,
        streaming: Boolean,
        tools: List<ToolSpec> = emptyList(),
    ): String {
        val root = mapper.createObjectNode()
        root.put("model", model.modelId)
        root.put("max_tokens", model.maxOutput ?: DEFAULT_MAX_TOKENS)
        if (streaming) root.put("stream", true)

        val system = turns.filter { it.role == "system" }.joinToString("\n\n") { it.content }
        if (system.isNotBlank()) root.put("system", system)

        val messages = root.putArray("messages")
        turns.filter { it.role != "system" }.forEach { turn ->
            val message = messages.addObject()
            when {
                // A tool result is a user turn holding a result block, which is
                // most of how this shape differs from the other one.
                turn.respondingTo != null -> {
                    message.put("role", "user")
                    message.putArray("content").addObject()
                        .put("type", "tool_result")
                        .put("tool_use_id", turn.respondingTo)
                        .put("content", turn.content)
                }

                turn.asked.isNotEmpty() -> {
                    message.put("role", turn.role)
                    val blocks = message.putArray("content")
                    if (turn.content.isNotEmpty()) {
                        blocks.addObject().put("type", "text").put("text", turn.content)
                    }
                    turn.asked.forEach { asked ->
                        val block = blocks.addObject()
                        block.put("type", "tool_use").put("id", asked.id).put("name", asked.name)
                        block.set("input", argumentsOf(asked.arguments))
                    }
                }

                else -> message.put("role", turn.role).put("content", turn.content)
            }
        }

        if (tools.isNotEmpty()) {
            val declared = root.putArray("tools")
            tools.forEach { tool ->
                val entry = declared.addObject()
                entry.put("name", tool.name).put("description", tool.description)
                val schema = entry.putObject("input_schema")
                schema.put("type", "object")
                val properties = schema.putObject("properties")
                tool.parameters.forEach { parameter ->
                    properties.putObject(parameter.name)
                        .put("type", "string")
                        .put("description", parameter.description)
                }
                val required = schema.putArray("required")
                tool.parameters.filter { it.required }.forEach { required.add(it.name) }
            }
        }
        return mapper.writeValueAsString(root)
    }

    /** Arguments arrive as a JSON string; this shape wants them as an object. */
    private fun argumentsOf(arguments: String): ObjectNode =
        runCatching { mapper.readTree(arguments) as? ObjectNode }.getOrNull() ?: mapper.createObjectNode()

    /** What the model asked for, in the OpenAI shape. */
    private fun openAiCalls(body: String): List<ToolCall> = runCatching {
        val calls = mapper.readTree(body).path("choices").firstOrNull()
            ?.path("message")?.path("tool_calls") as? ArrayNode ?: return emptyList()
        calls.mapNotNull { call ->
            val name = call.path("function").path("name").stringValue() ?: return@mapNotNull null
            ToolCall(
                id = call.path("id").stringValue().orEmpty(),
                name = name,
                arguments = call.path("function").path("arguments").stringValue().orEmpty(),
            )
        }
    }.getOrDefault(emptyList())

    /** And in the Anthropic shape, where it is a block among the content. */
    private fun anthropicCalls(body: String): List<ToolCall> = runCatching {
        val blocks = mapper.readTree(body).path("content") as? ArrayNode ?: return emptyList()
        blocks.filter { it.path("type").stringValue() == "tool_use" }.mapNotNull { block ->
            val name = block.path("name").stringValue() ?: return@mapNotNull null
            ToolCall(
                id = block.path("id").stringValue().orEmpty(),
                name = name,
                arguments = mapper.writeValueAsString(block.path("input")),
            )
        }
    }.getOrDefault(emptyList())

    private fun openAiContent(body: String): String? = runCatching {
        mapper.readTree(body).path("choices").firstOrNull()
            ?.path("message")?.path("content")?.stringValue()
    }.getOrNull()

    private fun anthropicContent(body: String): String? = runCatching {
        val blocks = mapper.readTree(body).path("content") as? ArrayNode ?: return null
        blocks.mapNotNull { it.path("text").stringValue() }.joinToString("").ifBlank { null }
    }.getOrNull()

    /**
     * What went wrong, in the provider's own words where it gave any. A refused
     * key and a bad request read very differently to whoever has to fix it.
     */
    private fun reason(status: Int, body: String): String {
        val message = runCatching {
            val tree = mapper.readTree(body)
            tree.path("error").path("message").stringValue() ?: tree.path("message").stringValue()
        }.getOrNull()
        return if (message.isNullOrBlank()) "The provider answered with $status" else "$status: $message"
    }

    private companion object {
        const val CONNECT_SECONDS = 10L
        const val DEFAULT_MAX_TOKENS = 4096
        const val ANTHROPIC_VERSION = "2023-06-01"
        const val DEFAULT_AZURE_VERSION = "2024-06-01"

        /** Every event in both shapes arrives on a `data:` line. */
        const val DATA_PREFIX = "data:"

        /** What the OpenAI shape closes with; Anthropic simply stops. */
        const val DONE = "[DONE]"
    }
}
