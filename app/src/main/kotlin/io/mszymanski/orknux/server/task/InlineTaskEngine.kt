package io.mszymanski.orknux.server.task

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.SmartLifecycle
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tasks carried by this process, for an installation with no Temporal.
 *
 * That is not a corner: the all-in-one image runs with
 * `ORKNUX_TEMPORAL_ENABLED=false`, so this is what most people who try the
 * product are actually running, and the suite runs on it too.
 *
 * It is a thread pool and a clock, and it keeps nothing in either of them. A
 * task's whole state is its row and its session, which is what lets this be as
 * simple as it is: a turn that is lost to a restart is a turn that was not
 * written down, and the one after it starts from what was. The revival on the
 * way back up is the other half of that - without it a task interrupted by a
 * restart would sit at RUNNING for ever, which is exactly what the inline
 * workflow engine beside this does and is worth not repeating.
 *
 * Unlike the workflow engine, a turn is **not** taken on the calling thread. A
 * task runs for as long as the work takes and nobody is waiting for it; a
 * mutation that returned when the task was finished would be an HTTP request
 * held open for an hour.
 *
 * What it cannot do is survive the machine. A task in flight when the process
 * dies loses the turn it was on, and one whose model call was half-answered
 * loses that answer. Anything that has to survive anything should be on
 * Temporal, which is the same sentence the workflow engine's own note ends with.
 */
@Service
@ConditionalOnProperty(name = ["orknux.temporal.enabled"], havingValue = "false", matchIfMissing = true)
@EnableConfigurationProperties(TaskProperties::class)
class InlineTaskEngine(
    private val loop: TaskLoop,
    private val tasks: TaskRepository,
    private val properties: TaskProperties,
) : TaskEngine, SmartLifecycle {

    /**
     * How many tasks may be working at once.
     *
     * Small on purpose. Every turn is a model call, and an installation running
     * without Temporal is one machine; letting twenty tasks think at once would
     * spend the whole of its model allowance on whichever four were started
     * first anyway.
     */
    private val workers: ScheduledExecutorService =
        Executors.newScheduledThreadPool(THREADS, named("orknux-task"))

    /**
     * Which tasks are already in hand, so a second `begin` or a nudge that
     * arrives while a turn is running does not start a second loop over the same
     * row.
     */
    private val inHand = ConcurrentHashMap.newKeySet<Long>()

    private var running = false

    override fun begin(taskId: Long) = submit(taskId)

    override fun nudge(taskId: Long) = submit(taskId)

    /**
     * Picks up whatever was in flight when this process last stopped.
     *
     * A task that was mid-turn is asked again from what it wrote down; a task
     * that was parked is put back on the clock, so its patience still runs out
     * even though the callback that would have noticed died with the process.
     */
    override fun start() {
        running = true
        val carried = tasks.inState(listOf(TaskStatus.QUEUED, TaskStatus.RUNNING, TaskStatus.WAITING))
        if (carried.isEmpty()) return
        log.info("Picking up {} task(s) left running", carried.size)
        carried.mapNotNull { it.id }.forEach(::submit)
    }

    override fun stop() {
        running = false
        workers.shutdownNow()
    }

    override fun isRunning(): Boolean = running

    private fun submit(taskId: Long) {
        if (!inHand.add(taskId)) return
        try {
            workers.execute { work(taskId) }
        } catch (refused: RuntimeException) {
            inHand.remove(taskId)
            log.warn("Task {} could not be picked up", taskId, refused)
        }
    }

    /**
     * Turns until it stops, then lets go.
     *
     * A parked task is not slept on here. It is put back on the clock at its
     * patience deadline and otherwise waits to be nudged, which is what an
     * approval does - so an answered task carries on at once, and an unanswered
     * one costs one callback a week rather than one every thirty seconds. That
     * is the one place this differs from Temporal, which polls because nothing
     * can reach across processes to wake it.
     */
    private fun work(taskId: Long) {
        try {
            while (running) {
                when (loop.advance(taskId)) {
                    is TaskTurn.Working -> Unit
                    is TaskTurn.Over -> return
                    is TaskTurn.Parked -> {
                        workers.schedule({ submit(taskId) }, properties.patience.toSeconds(), TimeUnit.SECONDS)
                        return
                    }
                }
            }
        } catch (failure: Exception) {
            // Nothing above this catches, so a turn that threw something the
            // loop did not expect would otherwise disappear into an executor.
            log.error("Task {} stopped on an unexpected failure", taskId, failure)
        } finally {
            inHand.remove(taskId)
            /*
             * A nudge that arrived while this was letting go was dropped: the
             * task was still in hand when it came, and nothing was left to hear
             * it a moment later. Looking once more after letting go is what
             * closes that window, and it costs one query per task that stops.
             */
            if (running && tasks.findByIdOrNull(taskId)?.status == TaskStatus.RUNNING) submit(taskId)
        }
    }

    private fun named(prefix: String) = object : ThreadFactory {
        private val next = AtomicInteger(1)
        override fun newThread(runnable: Runnable): Thread =
            Thread(runnable, "$prefix-${next.getAndIncrement()}").apply { isDaemon = true }
    }

    private companion object {
        const val THREADS = 4

        val log = LoggerFactory.getLogger(InlineTaskEngine::class.java)
    }
}
