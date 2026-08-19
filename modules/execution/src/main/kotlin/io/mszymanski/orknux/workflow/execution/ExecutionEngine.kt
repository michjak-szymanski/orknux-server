package io.mszymanski.orknux.workflow.execution

/**
 * Runs a workflow.
 *
 * Two implementations: [InlineExecutionEngine] carries the run out on the
 * calling thread, and the Temporal one hands it to a service that can retry a
 * step, outlive a restart and wait for hours. Which one is wired is
 * `orknux.temporal.enabled`.
 *
 * Both plan the same way — [ExecutionPlanner] — and both do the work of a step
 * the same way — [StepRunner] — so the only thing that differs is what carries
 * the run from one step to the next, and what happens when that is interrupted.
 */
interface ExecutionEngine {

    /**
     * Records the run and starts it. What comes back is the run as it stood
     * when this returned: finished, for the inline engine, and running for
     * Temporal, which answers as soon as the run is durably accepted.
     */
    fun start(
        workspaceId: Long,
        workflowId: Long,
        trigger: ExecutionTrigger,
        input: String? = null,
        /** Which copy to run, where what started it does not decide. See [StartExecutionInput]. */
        version: GraphVersion? = null,
        /**
         * Where to pick up an earlier run, instead of starting at the
         * beginning. The steps ahead of it are carried over from what that run
         * recorded, and this walks only what is left. See [ResumePoint].
         */
        resumeFrom: ResumePoint? = null,
    ): WorkflowExecution
}
