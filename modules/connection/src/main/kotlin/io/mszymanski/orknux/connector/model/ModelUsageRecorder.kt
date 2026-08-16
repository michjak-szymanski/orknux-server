package io.mszymanski.orknux.connector.model

import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate

/**
 * Counts what a model was actually used for.
 *
 * `model_usage_day` holds one row per model per day, and until now nothing wrote
 * to it — which is why the metrics screen reported an empty window however much
 * the chat was used. Every answered call adds itself here.
 *
 * In its own transaction: recording is bookkeeping about a call that already
 * happened, and a chat that answered should not be rolled back because the
 * counter could not be written.
 */
@Service
class ModelUsageRecorder(
    private val usage: ModelUsageRepository,
    /** Defaulted rather than a bean, the way `ModelService` takes one. */
    private val clock: Clock = Clock.systemDefaultZone(),
) {

    /**
     * Adds one call to today's row for this model, creating it when this is the
     * first call of the day.
     *
     * The unique key on (model, day) is what makes two calls at once safe: the
     * loser of the race gets a constraint violation, reads the row the winner
     * wrote, and adds itself to that.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun record(modelId: Long, inputTokens: Long, outputTokens: Long, millis: Long) {
        val today = LocalDate.now(clock)
        try {
            add(modelId, today, inputTokens, outputTokens, millis)
        } catch (_: DataIntegrityViolationException) {
            // Somebody else created today's row between the read and the write.
            runCatching { add(modelId, today, inputTokens, outputTokens, millis) }
                .onFailure { log.warn("Could not record usage for model {}", modelId, it) }
        }
    }

    private fun add(modelId: Long, day: LocalDate, inputTokens: Long, outputTokens: Long, millis: Long) {
        val row = usage.findByModelIdAndDay(modelId, day)
            ?: usage.save(ModelUsageDay(modelId = modelId, day = day))
        row.requests += 1
        row.inputTokens += inputTokens
        row.outputTokens += outputTokens
        // Summed, not averaged: the mean over a window is the total time over
        // the total requests, which is what `ModelService.usage` works out.
        row.latencyMillisTotal += millis
        usage.save(row)
    }

    private companion object {
        val log = LoggerFactory.getLogger(ModelUsageRecorder::class.java)
    }
}
