package io.mszymanski.orknux.connector.model

import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import io.mszymanski.orknux.connector.security.SECRET_COLUMN_LENGTH
import io.mszymanski.orknux.connector.security.SecretConverter
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.repository.JpaRepository
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime

/** What a model is for, which is what decides whether an agent may use it. */
enum class ModelKind {
    CHAT,
    EMBEDDING,
    COMPLETION,

    /** Speech in, text out: what the microphone in a chat is handed to. */
    TRANSCRIPTION,

    /** Text in, speech out: what reads an answer aloud. */
    SPEECH,
}

/** How often a token quota starts again. */
enum class ResetInterval {
    DAILY,
    WEEKLY,
    MONTHLY,

    /** The quota is a total, not a rate. */
    NEVER,
}

/**
 * The services a provider can be. Each brings its own settings and its own way in.
 *
 * A type is here because something branches on it. OPENAI is the shape the rest
 * are measured against; ANTHROPIC has its own body, its own streaming events and
 * its own `/messages` path; AZURE_OPENAI puts the deployment and the API version
 * in the URL and can authenticate through Entra ID; OLLAMA serves the OpenAI
 * shape under `/v1` of an address of your own, which is what [ModelProvider.openAiBase]
 * is for. GOOGLE_AI was removed in V170 because it branched on nothing except
 * the name of its auth header - see the migration.
 */
enum class ProviderType {
    OPENAI,
    ANTHROPIC,
    AZURE_OPENAI,
    OLLAMA,

    /** Anything that speaks one of the above well enough, until it earns a type. */
    CUSTOM,
}

/** How a provider is authenticated. */
enum class ProviderAuthMethod {
    /** A key sent on every request, in whichever header the type wants it. */
    API_KEY,

    /**
     * Microsoft Entra ID: a token fetched with a tenant, a client and a secret,
     * for the scope the resource asks for. Azure OpenAI only.
     */
    ENTRA_ID,
}

/**
 * What the Models screen says about a provider.
 *
 * CONNECTED only once a check reached the provider and it answered, which is
 * the same rule a workspace connection follows: a stored credential is not a working
 * one, and saying otherwise would be a guess dressed up as a fact.
 */
enum class ProviderStatus {
    /** Nothing to check with yet. */
    NOT_CONFIGURED,

    /** Configured, but no check has reached it. */
    NOT_CHECKED,
    CONNECTED,
    FAILED,
}

/**
 * An LLM provider a workspace reaches models through.
 *
 * It holds a key, which is why it lives in this module: credentials are read in
 * one place. Every provider authenticates the same way, so there is no auth
 * type to choose — a bearer key, or nothing configured yet.
 */
@Entity
@Table(name = "model_provider")
class ModelProvider(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "workspace_id", nullable = false)
    val workspaceId: Long,

    /** The display name, which is the workspace's to choose: "Azure OpenAI Production". */
    @Column(nullable = false, length = 120)
    var name: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    var type: ProviderType = ProviderType.OPENAI,

    @Column(nullable = false, length = 1000)
    var endpoint: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "auth_method", nullable = false, length = 16)
    var authMethod: ProviderAuthMethod = ProviderAuthMethod.API_KEY,

    /**
     * The API key, or the Entra client secret: one column, one place to read.
     *
     * Encrypted in the database. The column is wider than the value it holds
     * because the envelope is base64 and carries an initialisation vector.
     */
    @Convert(converter = SecretConverter::class)
    @Column(length = SECRET_COLUMN_LENGTH)
    var secret: String? = null,

    @Column(name = "api_version", length = 32)
    var apiVersion: String? = null,

    @Column(name = "deployment_name", length = 120)
    var deploymentName: String? = null,

    @Column(length = 64)
    var region: String? = null,

    @Column(name = "tenant_id", length = 120)
    var tenantId: String? = null,

    @Column(name = "client_id", length = 120)
    var clientId: String? = null,

    @Column(length = 300)
    var scope: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var status: ProviderStatus = ProviderStatus.NOT_CONFIGURED,

    @Column(name = "last_check_message", length = 500)
    var lastCheckMessage: String? = null,

    @Column(name = "last_checked_at")
    var lastCheckedAt: OffsetDateTime? = null,
) {

    /**
     * Whether there is enough here to try the provider at all.
     *
     * A key is enough on its own; Entra ID needs the three things the token
     * request is made of, and no amount of one of them substitutes for another.
     */
    fun configured(): Boolean = when (authMethod) {
        ProviderAuthMethod.API_KEY -> !secret.isNullOrBlank()
        ProviderAuthMethod.ENTRA_ID ->
            !secret.isNullOrBlank() && !tenantId.isNullOrBlank() && !clientId.isNullOrBlank()
    }

    /**
     * Where this provider's OpenAI-compatible surface begins.
     *
     * Every path this application builds for a provider that is not Anthropic or
     * Azure is an OpenAI-shaped one - `/models`, `/chat/completions`,
     * `/audio/speech` - hung off whatever endpoint somebody typed. That is right
     * for every type but one. Ollama listens on `http://host:11434`, which is
     * where an operator naturally points it, and serves none of those paths
     * there: its OpenAI surface is under `/v1`, and its own listing is
     * `/api/tags`.
     *
     * So the type supplies the segment rather than the operator. `/v1/models` is
     * chosen over the native `/api/tags` because the check has to prove the
     * surface the chat will actually use: `/api/tags` answering says the Ollama
     * daemon is up and says nothing about `/v1/chat/completions` being there,
     * which is 7876cdd's failure exactly - a check that reports Connected while
     * every message 404s. It also answers in the `data[].id` shape the rest of
     * the providers do, so a discovered id is the string the chat call is given.
     *
     * An endpoint already written `.../v1` - the workaround people have been
     * using - is left as it is rather than doubled.
     */
    fun openAiBase(): String {
        val base = endpoint.trimEnd('/')
        if (type != ProviderType.OLLAMA) return base
        return if (base.endsWith(OLLAMA_OPENAI_PATH)) base else "$base$OLLAMA_OPENAI_PATH"
    }

    /** Called after anything that could change whether it is worth checking. */
    fun forgetCheck() {
        status = if (configured()) ProviderStatus.NOT_CHECKED else ProviderStatus.NOT_CONFIGURED
        lastCheckMessage = null
        lastCheckedAt = null
    }

    private companion object {
        /** Ollama's OpenAI-compatible surface, which is not where it listens. */
        const val OLLAMA_OPENAI_PATH = "/v1"
    }
}

/**
 * One model the workspace may use, and the quotas the workspace puts on it.
 *
 * [name] is what a person calls it and [modelId] is what the provider's API is
 * given; they differ often enough — "Claude 3.5 Sonnet" against
 * `claude-3-5-sonnet-20241022` — that keeping one would lose the other.
 */
@Entity
@Table(name = "llm_model")
class LlmModel(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "provider_id", nullable = false)
    val providerId: Long,

    @Column(nullable = false, length = 120)
    var name: String,

    @Column(name = "model_id", nullable = false, length = 200)
    var modelId: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var kind: ModelKind = ModelKind.CHAT,

    @Column(name = "context_window")
    var contextWindow: Int? = null,

    @Column(name = "max_output")
    var maxOutput: Int? = null,

    @Column(nullable = false)
    var enabled: Boolean = true,

    /** Null is no limit. */
    @Column(name = "token_limit")
    var tokenLimit: Long? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "reset_interval", nullable = false, length = 16)
    var resetInterval: ResetInterval = ResetInterval.MONTHLY,

    @Column(name = "requests_per_minute")
    var requestsPerMinute: Int? = null,

    @Column(name = "input_cost_per_million", precision = 12, scale = 4)
    var inputCostPerMillion: BigDecimal? = null,

    @Column(name = "output_cost_per_million", precision = 12, scale = 4)
    var outputCostPerMillion: BigDecimal? = null,

    /**
     * Which voice a [ModelKind.SPEECH] model reads in; null sends none.
     *
     * The names belong to the provider — OpenAI knows `alloy`, a local server
     * knows its own — so this is text rather than a list of options this would
     * have to keep correct for every provider that exists.
     */
    @Column(length = 80)
    var voice: String? = null,
)

/**
 * What one model did on one day.
 *
 * Latency is summed rather than averaged, because an average of averages is not
 * an average: the mean over a window is the total time over the total requests.
 */
@Entity
@Table(name = "model_usage_day")
class ModelUsageDay(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "model_id", nullable = false)
    val modelId: Long,

    @Column(nullable = false)
    val day: LocalDate,

    @Column(nullable = false)
    var requests: Int = 0,

    @Column(name = "input_tokens", nullable = false)
    var inputTokens: Long = 0,

    @Column(name = "output_tokens", nullable = false)
    var outputTokens: Long = 0,

    @Column(name = "latency_millis_total", nullable = false)
    var latencyMillisTotal: Long = 0,
)

interface ModelProviderRepository : JpaRepository<ModelProvider, Long> {

    fun findByWorkspaceId(workspaceId: Long, sort: Sort): List<ModelProvider>

    fun findByWorkspaceIdAndName(workspaceId: Long, name: String): ModelProvider?
}

interface LlmModelRepository : JpaRepository<LlmModel, Long> {

    fun findByProviderIdIn(providerIds: Collection<Long>, sort: Sort): List<LlmModel>

    fun findByProviderIdAndName(providerId: Long, name: String): LlmModel?

    fun findByProviderId(providerId: Long): List<LlmModel>
}

interface ModelUsageRepository : JpaRepository<ModelUsageDay, Long> {

    fun findByModelIdAndDayBetweenOrderByDayAsc(
        modelId: Long,
        from: LocalDate,
        to: LocalDate,
    ): List<ModelUsageDay>

    /** The row a call adds itself to; there is one per model per day. */
    fun findByModelIdAndDay(modelId: Long, day: LocalDate): ModelUsageDay?
}

class ModelProviderNotFoundException(id: Long) : RuntimeException("No model provider with id $id")

class ModelProviderNameTakenException(name: String) :
    RuntimeException("A provider named \"$name\" already exists in this workspace")

class ModelProviderNameInvalidException : RuntimeException("A provider name is required")

class ModelProviderEndpointInvalidException : RuntimeException("A provider API endpoint is required")

class ModelNotFoundException(id: Long) : RuntimeException("No model with id $id")

class ModelNameTakenException(name: String) :
    RuntimeException("A model named \"$name\" already exists on this provider")

class ModelNameInvalidException : RuntimeException("A model name is required")

class ModelIdInvalidException : RuntimeException("A model id is required")

/**
 * Asking a provider what it offers did not get an answer.
 *
 * Carries the provider's own words, because "could not discover models" tells
 * nobody whether the key is wrong, the endpoint is wrong, or the box is off.
 */
class ModelDiscoveryFailedException(name: String, reason: String) :
    RuntimeException("Could not ask $name what it offers: $reason")
