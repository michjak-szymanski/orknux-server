package io.mszymanski.orknux.connector.model

import io.mszymanski.orknux.connector.connection.CheckOutcome
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.domain.Sort
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * The LLM providers a workspace has, the models it reaches through them, and what
 * those models have been used for.
 *
 * The metrics are a sum over `model_usage_day`, which nothing writes to yet:
 * there is no runtime that calls a model. A workspace that has not used one is told
 * so rather than shown a number, which is the same rule an action with no
 * runtime follows.
 */
@Service
class ModelService(
    private val providers: ModelProviderRepository,
    private val models: LlmModelRepository,
    private val usage: ModelUsageRepository,
    private val probe: ModelProviderProbe,
    private val events: ApplicationEventPublisher,
    /** Defaulted rather than a bean, the way `ConditionEvaluator` takes one. */
    private val clock: Clock = Clock.systemDefaultZone(),
) {

    fun providers(workspaceId: Long): List<ModelProviderView> =
        providers.findByWorkspaceId(workspaceId, Sort.by("name")).map(::ModelProviderView)

    fun provider(id: Long): ModelProviderView? =
        providers.findByIdOrNull(id)?.let(::ModelProviderView)

    /** Every model the workspace may reach, provider first and then name. */
    fun models(workspaceId: Long): List<LlmModelView> {
        val byId = providers.findByWorkspaceId(workspaceId, Sort.by("name")).associateBy { requireNotNull(it.id) }
        if (byId.isEmpty()) return emptyList()

        return models.findByProviderIdIn(byId.keys, Sort.by("name"))
            .map { LlmModelView(it, requireNotNull(byId[it.providerId])) }
            .sortedWith(compareBy({ it.providerName }, { it.name }))
    }

    fun model(id: Long): LlmModelView? {
        val model = models.findByIdOrNull(id) ?: return null
        val provider = providers.findByIdOrNull(model.providerId) ?: return null
        return LlmModelView(model, provider)
    }

    @Transactional
    fun createProvider(input: CreateProviderInput): ModelProviderView {
        val name = input.name.trim()
        val endpoint = input.endpoint.trim()
        if (name.isEmpty()) throw ModelProviderNameInvalidException()
        if (endpoint.isEmpty()) throw ModelProviderEndpointInvalidException()
        if (providers.findByWorkspaceIdAndName(input.workspaceId, name) != null) {
            throw ModelProviderNameTakenException(name)
        }

        val provider = ModelProvider(
            workspaceId = input.workspaceId,
            name = name,
            type = input.type ?: ProviderType.OPENAI,
            endpoint = endpoint,
            authMethod = input.authMethod ?: ProviderAuthMethod.API_KEY,
            secret = input.secret?.trim()?.ifEmpty { null },
            apiVersion = input.apiVersion?.trim()?.ifEmpty { null },
            deploymentName = input.deploymentName?.trim()?.ifEmpty { null },
            region = input.region?.trim()?.ifEmpty { null },
            tenantId = input.tenantId?.trim()?.ifEmpty { null },
            clientId = input.clientId?.trim()?.ifEmpty { null },
            scope = input.scope?.trim()?.ifEmpty { null },
        )
        provider.forgetCheck()
        val saved = providers.save(provider)
        // Checked as soon as the transaction lands, so a provider that was just
        // given a key does not sit on "Not checked" until the next sweep.
        events.publishEvent(ModelProviderSaved(requireNotNull(saved.id)))
        return ModelProviderView(saved)
    }

    /**
     * Backs the provider form; a null secret keeps the stored one, empty clears it.
     *
     * Anything saved here can change what a check would find, so the last one is
     * forgotten rather than left to describe a provider that has moved.
     */
    @Transactional
    fun updateProvider(id: Long, input: UpdateProviderInput): ModelProviderView {
        val provider = providers.findByIdOrNull(id) ?: throw ModelProviderNotFoundException(id)

        val name = input.name.trim()
        val endpoint = input.endpoint.trim()
        if (name.isEmpty()) throw ModelProviderNameInvalidException()
        if (endpoint.isEmpty()) throw ModelProviderEndpointInvalidException()
        if (name != provider.name && providers.findByWorkspaceIdAndName(provider.workspaceId, name) != null) {
            throw ModelProviderNameTakenException(name)
        }

        provider.name = name
        provider.endpoint = endpoint
        input.type?.let { provider.type = it }
        input.authMethod?.let { provider.authMethod = it }
        input.secret?.let { provider.secret = it.trim().ifEmpty { null } }
        provider.apiVersion = input.apiVersion?.trim()?.ifEmpty { null }
        provider.deploymentName = input.deploymentName?.trim()?.ifEmpty { null }
        provider.region = input.region?.trim()?.ifEmpty { null }
        provider.tenantId = input.tenantId?.trim()?.ifEmpty { null }
        provider.clientId = input.clientId?.trim()?.ifEmpty { null }
        provider.scope = input.scope?.trim()?.ifEmpty { null }

        provider.forgetCheck()
        events.publishEvent(ModelProviderSaved(id))
        return ModelProviderView(provider)
    }

    /**
     * Asks the provider whether it is there, and records what it said.
     *
     * This is the Test Connection button. What comes back is the provider's own
     * answer — a refused credential is a failure, anything else that replies is
     * a provider that can be reached.
     */
    @Transactional
    fun testProvider(id: Long): ModelProviderView {
        val provider = providers.findByIdOrNull(id) ?: throw ModelProviderNotFoundException(id)

        val result = probe.check(provider)
        provider.status = when (result.outcome) {
            CheckOutcome.CONNECTED -> ProviderStatus.CONNECTED
            CheckOutcome.FAILED -> ProviderStatus.FAILED
        }
        provider.lastCheckMessage = result.message.take(MESSAGE_LENGTH)
        provider.lastCheckedAt = OffsetDateTime.now(clock)
        return ModelProviderView(provider)
    }

    /**
     * What the provider offers, against what the workspace has already added.
     *
     * A suggestion, not an import. Half of a model row is workspace policy —
     * what people call it, its quotas, whether it is on — and none of that is
     * discoverable. What is discoverable is the id, which is the part nobody
     * should have to transcribe from a provider's documentation.
     *
     * Nothing is written here, so this is a query: asking what is on offer
     * changes nothing until somebody adds one.
     */
    fun discoverModels(providerId: Long): List<DiscoveredModelView> {
        val provider = providers.findByIdOrNull(providerId) ?: throw ModelProviderNotFoundException(providerId)

        val offered = when (val listing = probe.list(provider)) {
            is ModelProviderProbe.Listing.Failed -> throw ModelDiscoveryFailedException(provider.name, listing.reason)
            is ModelProviderProbe.Listing.Models -> listing.ids
        }

        val added = models.findByProviderIdIn(setOf(providerId), Sort.by("name")).map { it.modelId }.toSet()
        return offered.sorted().map { DiscoveredModelView(modelId = it, added = it in added) }
    }

    /** Takes the models with it, which is what the cascade is for. */
    @Transactional
    fun removeProvider(id: Long): Boolean {
        val provider = providers.findByIdOrNull(id) ?: return false
        providers.delete(provider)
        return true
    }

    @Transactional
    fun createModel(input: CreateModelInput): LlmModelView {
        val provider = providers.findByIdOrNull(input.providerId)
            ?: throw ModelProviderNotFoundException(input.providerId)

        val name = input.name.trim()
        val modelId = input.modelId.trim()
        if (name.isEmpty()) throw ModelNameInvalidException()
        if (modelId.isEmpty()) throw ModelIdInvalidException()
        if (models.findByProviderIdAndName(input.providerId, name) != null) {
            throw ModelNameTakenException(name)
        }

        val model = models.save(
            LlmModel(
                providerId = input.providerId,
                name = name,
                modelId = modelId,
                kind = input.kind ?: ModelKind.CHAT,
                contextWindow = input.contextWindow,
                maxOutput = input.maxOutput,
                tokenLimit = input.tokenLimit,
                resetInterval = input.resetInterval ?: ResetInterval.MONTHLY,
                requestsPerMinute = input.requestsPerMinute,
                inputCostPerMillion = input.inputCostPerMillion?.toBigDecimal(),
                outputCostPerMillion = input.outputCostPerMillion?.toBigDecimal(),
            ),
        )
        return LlmModelView(model, provider)
    }

    /**
     * Backs the model's own details: what it is called, what the API is given,
     * and what it costs. The form sends all of them, so a null is a cleared
     * field rather than one nobody mentioned.
     */
    @Transactional
    fun updateModel(id: Long, input: UpdateModelInput): LlmModelView {
        val model = models.findByIdOrNull(id) ?: throw ModelNotFoundException(id)
        val provider = providers.findByIdOrNull(model.providerId)
            ?: throw ModelProviderNotFoundException(model.providerId)

        val name = input.name.trim()
        val modelId = input.modelId.trim()
        if (name.isEmpty()) throw ModelNameInvalidException()
        if (modelId.isEmpty()) throw ModelIdInvalidException()
        if (name != model.name && models.findByProviderIdAndName(model.providerId, name) != null) {
            throw ModelNameTakenException(name)
        }

        model.name = name
        model.modelId = modelId
        model.kind = input.kind ?: model.kind
        model.contextWindow = input.contextWindow
        model.maxOutput = input.maxOutput
        model.inputCostPerMillion = input.inputCostPerMillion?.toBigDecimal()
        model.outputCostPerMillion = input.outputCostPerMillion?.toBigDecimal()
        return LlmModelView(model, provider)
    }

    /**
     * Backs the Quotas and Limits card, which saves its three fields together.
     *
     * A null is no limit — an emptied box on that form means the workspace took the
     * limit off, which is a thing it has to be able to do.
     */
    @Transactional
    fun updateModelQuotas(id: Long, input: ModelQuotasInput): LlmModelView {
        val model = models.findByIdOrNull(id) ?: throw ModelNotFoundException(id)
        val provider = providers.findByIdOrNull(model.providerId)
            ?: throw ModelProviderNotFoundException(model.providerId)

        model.tokenLimit = input.tokenLimit
        model.resetInterval = input.resetInterval
        model.requestsPerMinute = input.requestsPerMinute
        return LlmModelView(model, provider)
    }

    @Transactional
    fun setModelEnabled(id: Long, enabled: Boolean): LlmModelView {
        val model = models.findByIdOrNull(id) ?: throw ModelNotFoundException(id)
        val provider = providers.findByIdOrNull(model.providerId)
            ?: throw ModelProviderNotFoundException(model.providerId)

        model.enabled = enabled
        return LlmModelView(model, provider)
    }

    @Transactional
    fun removeModel(id: Long): Boolean {
        val model = models.findByIdOrNull(id) ?: return false
        models.delete(model)
        return true
    }

    /** Hands the stored key back, for the provider form's "Reveal" action. */
    @Transactional
    fun revealProviderSecret(id: Long): String? {
        val provider = providers.findByIdOrNull(id) ?: throw ModelProviderNotFoundException(id)
        log.info("Credentials for model provider {} (workspace {}) revealed", provider.name, provider.workspaceId)
        return provider.secret
    }

    /**
     * What one model has been used for over the last [days], and how that
     * compares with the [days] before it.
     *
     * Everything here is a sum over the recorded days, so a model nothing has
     * called reports zero and says the window is empty, rather than showing a
     * shape with no numbers behind it.
     */
    fun usage(modelId: Long, days: Int = USAGE_DAYS): ModelUsageView {
        val model = models.findByIdOrNull(modelId) ?: throw ModelNotFoundException(modelId)

        val today = LocalDate.now(clock)
        val from = today.minusDays((days - 1).toLong())
        val previousFrom = from.minusDays(days.toLong())

        val current = usage.findByModelIdAndDayBetweenOrderByDayAsc(modelId, from, today)
        val previous = usage.findByModelIdAndDayBetweenOrderByDayAsc(modelId, previousFrom, from.minusDays(1))

        val totals = Totals(current)
        val before = Totals(previous)

        // The quota runs on its own clock, which is rarely the reporting window.
        val periodStart = periodStart(model.resetInterval, today)
        val inPeriod = Totals(usage.findByModelIdAndDayBetweenOrderByDayAsc(modelId, periodStart, today))

        return ModelUsageView(
            modelId = modelId,
            days = days,
            from = from,
            to = today,
            requests = totals.requests,
            inputTokens = totals.inputTokens,
            outputTokens = totals.outputTokens,
            totalTokens = totals.tokens,
            averageLatencyMillis = totals.averageLatencyMillis,
            costEstimate = cost(model, totals),
            requestsChange = change(totals.requests.toDouble(), before.requests.toDouble()),
            tokensChange = change(totals.tokens.toDouble(), before.tokens.toDouble()),
            latencyChange = change(totals.averageLatencyMillis, before.averageLatencyMillis),
            series = current.map { ModelUsageDayView(it.day, it.requests, it.inputTokens + it.outputTokens) },
            periodStart = periodStart,
            periodTokens = inPeriod.tokens,
        )
    }

    /**
     * When the current quota period began.
     *
     * A quota that never resets counts from the first day anything was recorded,
     * which for these purposes is far enough back to be "everything".
     */
    private fun periodStart(interval: ResetInterval, today: LocalDate): LocalDate = when (interval) {
        ResetInterval.DAILY -> today
        ResetInterval.WEEKLY -> today.minusDays((today.dayOfWeek.value - 1).toLong())
        ResetInterval.MONTHLY -> today.withDayOfMonth(1)
        ResetInterval.NEVER -> today.minus(FOREVER_YEARS, ChronoUnit.YEARS)
    }

    /**
     * What the tokens cost, at what the provider is recorded as charging. Null
     * when the model carries no prices, because a guess would be worse.
     */
    private fun cost(model: LlmModel, totals: Totals): BigDecimal? {
        val input = model.inputCostPerMillion
        val output = model.outputCostPerMillion
        if (input == null && output == null) return null

        val perMillion = { tokens: Long, price: BigDecimal? ->
            price?.multiply(BigDecimal(tokens))?.divide(MILLION, COST_SCALE, RoundingMode.HALF_UP) ?: BigDecimal.ZERO
        }
        return perMillion(totals.inputTokens, input).add(perMillion(totals.outputTokens, output))
    }

    /** The change from one window to the next, as a fraction. Null when there was nothing to compare with. */
    private fun change(now: Double, before: Double): Double? {
        if (before == 0.0) return null
        return (now - before) / before
    }

    /** One window's worth of days, added up. */
    private class Totals(days: List<ModelUsageDay>) {
        val requests: Int = days.sumOf { it.requests }
        val inputTokens: Long = days.sumOf { it.inputTokens }
        val outputTokens: Long = days.sumOf { it.outputTokens }
        val tokens: Long = inputTokens + outputTokens
        val averageLatencyMillis: Double =
            if (requests == 0) 0.0 else days.sumOf { it.latencyMillisTotal }.toDouble() / requests
    }

    private companion object {
        val log = LoggerFactory.getLogger(ModelService::class.java)

        /** The window the metrics card reports on. */
        const val USAGE_DAYS = 30

        /** Matches the column. */
        const val MESSAGE_LENGTH = 500
        const val FOREVER_YEARS = 100L
        const val COST_SCALE = 2

        val MILLION: BigDecimal = BigDecimal(1_000_000)
    }
}

data class CreateProviderInput(
    val workspaceId: Long,
    val name: String,
    val endpoint: String,
    val type: ProviderType? = null,
    val authMethod: ProviderAuthMethod? = null,
    /** The API key, or the Entra client secret. */
    val secret: String? = null,
    val apiVersion: String? = null,
    val deploymentName: String? = null,
    val region: String? = null,
    val tenantId: String? = null,
    val clientId: String? = null,
    val scope: String? = null,
)

data class UpdateProviderInput(
    val name: String,
    val endpoint: String,
    val type: ProviderType? = null,
    val authMethod: ProviderAuthMethod? = null,
    /** Null leaves the stored credential alone; empty clears it. */
    val secret: String? = null,
    val apiVersion: String? = null,
    val deploymentName: String? = null,
    val region: String? = null,
    val tenantId: String? = null,
    val clientId: String? = null,
    val scope: String? = null,
)

data class CreateModelInput(
    val providerId: Long,
    val name: String,
    val modelId: String,
    val kind: ModelKind? = null,
    val contextWindow: Int? = null,
    val maxOutput: Int? = null,
    val tokenLimit: Long? = null,
    val resetInterval: ResetInterval? = null,
    val requestsPerMinute: Int? = null,
    val inputCostPerMillion: Double? = null,
    val outputCostPerMillion: Double? = null,
)

/** The model's own details, all of them, as the form that edits them sends them. */
data class UpdateModelInput(
    val name: String,
    val modelId: String,
    val kind: ModelKind? = null,
    val contextWindow: Int? = null,
    val maxOutput: Int? = null,
    val inputCostPerMillion: Double? = null,
    val outputCostPerMillion: Double? = null,
)

/** The Quotas and Limits card, which saves its fields together. Null is no limit. */
data class ModelQuotasInput(
    val tokenLimit: Long? = null,
    val resetInterval: ResetInterval = ResetInterval.MONTHLY,
    val requestsPerMinute: Int? = null,
)

data class ModelProviderView(
    val id: Long,
    val workspaceId: Long,
    val name: String,
    val type: ProviderType,
    val endpoint: String,
    val authMethod: ProviderAuthMethod,
    val apiVersion: String?,
    val deploymentName: String?,
    val region: String?,
    val tenantId: String?,
    val clientId: String?,
    val scope: String?,
    val status: ProviderStatus,
    val lastCheckMessage: String?,
    /** ISO-8601, as `WorkspaceConnectionView` reports its own. */
    val lastCheckedAt: String?,
    val secretSet: Boolean,
) {
    constructor(provider: ModelProvider) : this(
        id = requireNotNull(provider.id),
        workspaceId = provider.workspaceId,
        name = provider.name,
        type = provider.type,
        endpoint = provider.endpoint,
        authMethod = provider.authMethod,
        apiVersion = provider.apiVersion,
        deploymentName = provider.deploymentName,
        region = provider.region,
        tenantId = provider.tenantId,
        clientId = provider.clientId,
        scope = provider.scope,
        status = provider.status,
        lastCheckMessage = provider.lastCheckMessage,
        lastCheckedAt = provider.lastCheckedAt?.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        secretSet = !provider.secret.isNullOrBlank(),
    )
}

data class LlmModelView(
    val id: Long,
    val providerId: Long,
    val workspaceId: Long,
    val providerName: String,
    val name: String,
    val modelId: String,
    val kind: ModelKind,
    val contextWindow: Int?,
    val maxOutput: Int?,
    val enabled: Boolean,
    val tokenLimit: Long?,
    val resetInterval: ResetInterval,
    val requestsPerMinute: Int?,
    val inputCostPerMillion: Double?,
    val outputCostPerMillion: Double?,
) {
    constructor(model: LlmModel, provider: ModelProvider) : this(
        id = requireNotNull(model.id),
        providerId = model.providerId,
        workspaceId = provider.workspaceId,
        providerName = provider.name,
        name = model.name,
        modelId = model.modelId,
        kind = model.kind,
        contextWindow = model.contextWindow,
        maxOutput = model.maxOutput,
        enabled = model.enabled,
        tokenLimit = model.tokenLimit,
        resetInterval = model.resetInterval,
        requestsPerMinute = model.requestsPerMinute,
        inputCostPerMillion = model.inputCostPerMillion?.toDouble(),
        outputCostPerMillion = model.outputCostPerMillion?.toDouble(),
    )
}

/**
 * A model the provider says it can run.
 *
 * [added] rather than filtering the ones already in the catalogue out: a picker
 * that silently drops them looks like the provider stopped offering them.
 */
data class DiscoveredModelView(
    val modelId: String,
    val added: Boolean,
)

/** One day of the chart. */
data class ModelUsageDayView(
    val day: LocalDate,
    val requests: Int,
    val tokens: Long,
)

data class ModelUsageView(
    val modelId: Long,
    val days: Int,
    val from: LocalDate,
    val to: LocalDate,
    val requests: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val totalTokens: Long,
    val averageLatencyMillis: Double,
    /** Null when the model carries no prices to work it out from. */
    val costEstimate: BigDecimal?,
    /** Fractions against the window before this one; null when there was none. */
    val requestsChange: Double?,
    val tokensChange: Double?,
    val latencyChange: Double?,
    /** Only the days something happened on, so an untouched model has none. */
    val series: List<ModelUsageDayView>,
    /** Where the quota is counting from, which follows the reset interval. */
    val periodStart: LocalDate,
    val periodTokens: Long,
) {
    /** True when nothing has been recorded, which the screen says rather than showing zeros as a result. */
    val empty: Boolean get() = series.isEmpty()
}
