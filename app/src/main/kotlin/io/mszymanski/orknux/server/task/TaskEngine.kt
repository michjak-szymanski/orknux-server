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

    /**
     * Take a task back that nothing is carrying any more.
     *
     * What [TaskSweeper] calls, and the one thing on this interface that is
     * allowed to be asked about a task somebody else may already hold - so the
     * whole of it is the answer to "am I about to run this twice". Both
     * implementations refuse rather than start a second turn, and each has its
     * own reason to be able to:
     *
     *  - inline, a task in flight is in `inHand` from before it reaches a
     *    worker until after its last turn, so this is a set membership test;
     *  - on Temporal, a task in flight is a running workflow whose id is the
     *    task's, and the server refuses a second start of it. Neither answer
     *    is a guess about timing.
     *
     * Returns true when this call is what put the task back to work, which is
     * what the sweep counts and what a test asserts on. False means somebody
     * already had it, and is the ordinary answer.
     */
    fun recover(taskId: Long): Boolean
}
