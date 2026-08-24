package io.mszymanski.orknux.server.task

/**
 * What carries a task from one turn to the next.
 *
 * The same seam a workflow run has, and for the same reason: the work of a turn
 * is [TaskLoop]'s either way, and what differs is only what holds the task
 * between two of them and what happens when that is interrupted.
 *
 * Two implementations, chosen by `orknux.temporal.enabled`. On Temporal a task
 * is a durable workflow: it survives a restart of every process involved, and it
 * can sit parked for a week without holding anything. Inline it is a thread in
 * this process and a scheduled callback, which survives a restart only because
 * nothing is kept in either of them - the task's whole state is its row and its
 * session, so a restart picks it up where it was.
 */
interface TaskEngine {

    /**
     * Start working on it.
     *
     * Called once, after the task is recorded. Asking twice for the same task
     * must not start it twice - both implementations are keyed on the task's id
     * for that reason.
     */
    fun begin(taskId: Long)

    /**
     * Something changed that a parked task was waiting for.
     *
     * A hint and not a promise: a task that is not parked, or is parked in
     * another process, is unaffected, and it will find out for itself when it
     * next looks. Nothing about correctness depends on this arriving - it is
     * what makes an approval take effect now rather than at the next poll.
     */
    fun nudge(taskId: Long)
}
