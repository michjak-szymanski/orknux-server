package io.mszymanski.orknux.workflow.execution

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Service

/**
 * What a scrape can be alerted on: that runs are still arriving, and how they end.
 *
 * Three series and no more. The JVM, the HTTP layer and the connection pool are
 * already measured by what Boot brings, and a counter per node kind or per
 * workspace would be a dashboard nobody reads and a series count that grows with
 * use. These two are the ones that mean something is wrong with the platform
 * rather than with a machine:
 *
 *  - failed against completed is the failure rate, and a workflow estate that
 *    starts failing does so quietly - every run is recorded and nothing shouts.
 *  - started against the sum of the other two is what is still in flight. It
 *    grows and stays grown when runs stop finishing, which is what a restart
 *    part way through leaves behind under the inline engine, and what a stalled
 *    Temporal worker looks like from here.
 *
 * All three are registered when this is built rather than on first use, so a
 * fresh installation exposes them reading zero. A counter that does not exist
 * until the first failure is a counter no alert can be written against: `rate()`
 * over an absent series is absent, not zero, and the alert that was meant to
 * fire on the first failure is the one that has never had a series to watch.
 */
@Service
class WorkflowRunMetrics(registry: MeterRegistry) {

    private val started: Counter = Counter.builder(RUNS_STARTED)
        .description("Workflow runs recorded, whichever engine carries them out")
        .register(registry)

    private val completed: Counter = finished(registry, "completed")

    private val failed: Counter = finished(registry, "failed")

    /** A run has been written down and is about to be carried out. */
    fun runStarted() = started.increment()

    /** A run reached the end, or a node told it there was nothing further to do. */
    fun runCompleted() = completed.increment()

    /** A step could not be carried out and the run stopped there. */
    fun runFailed() = failed.increment()

    private fun finished(registry: MeterRegistry, outcome: String) = Counter.builder(RUNS_FINISHED)
        .description("Workflow runs that reached an end, by how they ended")
        .tag("outcome", outcome)
        .register(registry)

    private companion object {
        const val RUNS_STARTED = "orknux.workflow.runs.started"
        const val RUNS_FINISHED = "orknux.workflow.runs.finished"
    }
}
