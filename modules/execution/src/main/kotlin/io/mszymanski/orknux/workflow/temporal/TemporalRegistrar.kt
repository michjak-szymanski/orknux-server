package io.mszymanski.orknux.workflow.temporal

import io.temporal.worker.Worker

/**
 * Something else this process wants Temporal to run.
 *
 * There is one worker polling one queue, and `newWorker` on the same queue twice
 * is an error - so anything outside this module that has a durable loop of its
 * own cannot simply build one. This is the seam it registers through instead.
 *
 * It exists for the same reason [io.mszymanski.orknux.workflow.execution.NodeRunner]
 * does: this module owns the machinery and `app` owns everything the machinery
 * is pointed at. A task's loop calls models, reads an agent's grants and writes
 * an LLM session, none of which this module knows about and none of which it
 * should - so the loop lives in `app` and arrives here as one implementation of
 * one interface.
 *
 * A registrar is handed the worker before it starts polling. It should register
 * workflow implementation types and activity implementations and do nothing
 * else.
 */
interface TemporalRegistrar {

    fun register(worker: Worker)
}
