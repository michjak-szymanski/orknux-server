package io.mszymanski.orknux.server.stream

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Noticing that nobody is reading a stream any more, while it is still being
 * written.
 *
 * **Why this has to ask rather than wait to be told.** A servlet container on
 * blocking IO does not report a browser that has gone: it finds out on the next
 * write, and until then the request is a thread happily doing work for nobody.
 * A chat's answer is written by one long call into a model, and between the
 * question and the first word of the answer there is nothing to write at all —
 * seconds for an ordinary model, minutes for one that thinks, and the whole of
 * an agent's tool loop, whose rounds report a lookup and then say nothing while
 * the answer is composed. So the case that matters most — somebody pressing the
 * circle in voice mode while it is still thinking — is exactly the case where
 * the container has nothing to discover the hang-up on.
 *
 * That was issue #299. Interrupting stopped the browser listening and stopped
 * nothing else: the model went on writing an answer nobody would read, the
 * tokens went on being charged for, and the next thing the person said raced a
 * turn that was still in flight.
 *
 * So this pings. A server-sent comment is two bytes, means nothing to the
 * browser's parser, and is the protocol's own way of asking whether anybody is
 * still there. The write that fails is the answer.
 *
 * **And it is a keep-alive as well as a question**, which is the same frame
 * doing the second job it was invented for: a chat that thinks for two minutes
 * behind a proxy with a sixty second idle timeout was being cut off by the
 * proxy, and nothing in the chat stream had ever sent one. [TaskStreamAPI] sends
 * these on its own loop for exactly that reason; the chat had no loop to send
 * them from, and this is it.
 *
 * One thread serves every stream in the process. Each ping is a couple of bytes
 * onto an already-open socket, so a hundred conversations in flight is a hundred
 * writes a second on one thread — and the alternative, a thread per answer, is
 * a thread per answer.
 */
@Component
class ReaderWatch {

    /**
     * The timer, shared, and daemon threads so it never holds the process open.
     *
     * Scheduled rather than a thread per stream for the reason above, and a
     * handful of threads rather than one because a ping is a write and a write
     * to a socket whose far end has stopped reading can sit there. It never
     * queues behind another thread's frame — [ServerSentEvents.keepAlive]
     * declines the lock rather than waiting for it — so what is left is a
     * genuinely wedged connection, and a few threads is enough that one of
     * those does not decide how long every other conversation waits to find out
     * it has been abandoned.
     */
    private val timer: ScheduledExecutorService =
        Executors.newScheduledThreadPool(THREADS) { runnable ->
            Thread(runnable, "reader-watch").apply { isDaemon = true }
        }

    /**
     * Runs [work], pinging the reader until it is done.
     *
     * @param gone called once, on the timer's thread, the first time a ping
     *   fails. Whatever it does has to be safe to do from a thread other than
     *   the one inside [work] — which is the whole reason
     *   [io.mszymanski.orknux.connector.model.Hangup] exists.
     * @return whatever [work] returned. Nothing here changes that: this decides
     *   when to give up, not what a given-up call answers.
     *
     * The ping stops when [work] returns, however it returns. A stream whose
     * work threw is a stream about to be closed by the container, and a timer
     * still writing to it would be writing to a response nobody owns.
     */
    fun <T> whileReading(stream: ServerSentEvents, gone: () -> Unit, work: () -> T): T {
        val told = AtomicBoolean(false)
        val ping = timer.scheduleWithFixedDelay(
            {
                runCatching { stream.keepAlive() }.onFailure {
                    // The first failure is the answer, and there is no second
                    // question worth asking: `told` is what keeps this from
                    // giving up twice while the schedule winds down.
                    if (told.compareAndSet(false, true)) {
                        log.debug("A stream's reader has gone", it)
                        runCatching(gone).onFailure { failure -> log.warn("Could not give up on a stream", failure) }
                    }
                }
            },
            EVERY_MILLIS,
            EVERY_MILLIS,
            TimeUnit.MILLISECONDS,
        )
        return try {
            work()
        } finally {
            ping.cancel(false)
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(ReaderWatch::class.java)

        /** Enough that one wedged socket is not the whole watch. */
        const val THREADS = 2

        /**
         * How long a reader who has gone goes unnoticed for.
         *
         * A second, because this is what a person pressing stop is waiting on
         * and the thing they pressed stop on is being charged for by the token.
         * The task stream's own keep-alive is twenty seconds apart, and rightly:
         * there it is only holding a proxy open, and nothing is spent while it
         * waits.
         */
        const val EVERY_MILLIS = 1_000L
    }
}
