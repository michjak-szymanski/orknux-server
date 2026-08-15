package io.mszymanski.gyloli.workflow.execution

import org.springframework.stereotype.Service
import java.time.OffsetDateTime

/**
 * The log one run produced.
 *
 * The sequence number is read from what is already there rather than counted in
 * memory, because a run's steps are not all written by the same process: with
 * Temporal each step is an activity, and the one that writes the next line may
 * be on another worker after a restart.
 */
@Service
class RunLogger(private val logs: ExecutionLogRepository) {

    fun write(executionId: Long, nodeKey: String?, level: LogLevel, message: String) {
        logs.save(
            ExecutionLog(
                executionId = executionId,
                nodeKey = nodeKey,
                loggedAt = OffsetDateTime.now(),
                level = level,
                message = message.take(MESSAGE_LENGTH),
                sequence = logs.countByExecutionId(executionId),
            ),
        )
    }

    private companion object {
        /** Matches the column; a stack trace pasted into a message must not fail the insert. */
        const val MESSAGE_LENGTH = 2000
    }
}
