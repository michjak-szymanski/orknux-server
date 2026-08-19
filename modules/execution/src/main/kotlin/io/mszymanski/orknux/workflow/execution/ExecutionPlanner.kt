package io.mszymanski.orknux.workflow.execution

import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.time.OffsetDateTime

/** A run, recorded and ready to be carried out. */
data class ExecutionPlan(
    val execution: WorkflowExecution,
    /** In the order they are to be run. */
    val steps: List<ExecutionStep>,
    /**
     * The graph's edges, carried so an engine can tell what leads where.
     *
     * A plan used to be a list and nothing else, because every node ran. With
     * branches an engine has to know which node a step follows from, and
     * whether the edge between them was the answer the condition gave.
     */
    val edges: List<GraphEdge> = emptyList(),
    /**
     * The steps an earlier run already took, for a plan that begins partway
     * down the graph.
     *
     * Empty for an ordinary run. Where it is not, an engine opens these exits
     * before it walks anything: the chosen node is reached because the node
     * before it was reached in the earlier run, and nothing in this run is
     * going to say so.
     */
    val carried: List<CarriedExit> = emptyList(),
)

/**
 * A step an earlier run took, and which way out of it that run went.
 *
 * [branch] is null for everything that answers nothing, which is every kind but
 * a condition, and means the same here as it does live: every edge out of the
 * node leads somewhere.
 */
data class CarriedExit(val nodeKey: String, val branch: EdgeBranch? = null)

/**
 * Where a re-run picks up: the run to read what happened from, and the node to
 * begin at.
 *
 * The two are inseparable. Starting at a node means starting with what the
 * graph had produced by the time that node was reached, and the only place that
 * exists is the earlier run's record.
 */
data class ResumePoint(val executionId: Long, val nodeKey: String)

/**
 * Turns a request to run something into a recorded run: reads the graph from
 * orknux-server, works out an order, and writes the run and its steps down.
 *
 * This happens while the caller is waiting, on purpose. A workflow that cannot
 * be read or cannot be ordered is a rejected request rather than a failed run,
 * and the caller is told so with an error it can act on. Only what comes after
 * — the running — is worth making durable.
 */
@Service
class ExecutionPlanner(
    private val graphs: WorkflowGraphSource,
    private val mapper: ObjectMapper,
    private val executions: WorkflowExecutionRepository,
    private val steps: ExecutionStepRepository,
    private val log: RunLogger,
) {

    /**
     * @param resumeFrom where to pick up an earlier run, instead of starting at
     *   the beginning. The steps ahead of that node are copied from what the
     *   earlier run recorded rather than performed again, and the run starts
     *   holding what the earlier one was holding when it reached that node.
     */
    fun plan(
        workspaceId: Long,
        workflowId: Long,
        trigger: ExecutionTrigger,
        input: String?,
        asked: GraphVersion? = null,
        resumeFrom: ResumePoint? = null,
    ): ExecutionPlan {
        /*
         * A person pressing Run means the graph on their screen; anything else
         * means the graph that was published. The distinction is the whole of
         * what publishing buys - an event arriving mid-edit must not run what
         * is half-drawn - and it is read off what started the run rather than
         * from a setting somebody has to remember.
         */
        val version = asked ?: if (trigger == ExecutionTrigger.MANUAL) GraphVersion.DRAFT else GraphVersion.PUBLISHED
        val graph = graphs.graph(workspaceId, workflowId, version)
        val order = graph.runOrder()

        // Read and checked before anything is written down, so a re-run that
        // cannot honestly be started leaves no half-run behind to explain.
        val earlier = resumeFrom?.let { earlierRun(it, graph, order) }

        val execution = executions.save(
            WorkflowExecution(
                workspaceId = workspaceId,
                workflowId = graph.workflowId,
                workflowName = graph.name,
                status = ExecutionStatus.RUNNING,
                trigger = trigger,
                startedAt = OffsetDateTime.now(),
                input = input,
                // What the earlier run had produced by the time it reached the
                // chosen node. The first step of a re-run is handed exactly
                // what it was handed before, which is the point of the exercise.
                carried = earlier?.carried,
            ),
        )
        val executionId = requireNotNull(execution.id)

        val recorded = steps.saveAll(
            order.mapIndexed { index, node ->
                val before = earlier?.taken?.get(node.key)
                ExecutionStep(
                    executionId = executionId,
                    nodeKey = node.key,
                    kind = node.kind,
                    name = node.name,
                    description = node.description,
                    actionId = node.actionId,
                    conditionId = node.conditionId,
                    agentId = node.agentId,
                    outputName = node.outputName,
                    // The run's own copy of what to pass; see ExecutionStep.
                    mappings = node.mappings.takeIf { it.isNotEmpty() }?.let(mapper::writeValueAsString),
                    x = node.x,
                    y = node.y,
                    order = index,
                    /*
                     * A step ahead of the chosen one appears as what it was,
                     * marked as carried over. The alternative was to leave it
                     * pending, which reads as a run that never started and
                     * throws away the one thing somebody looking at a re-run
                     * wants to see: what fed it.
                     */
                    status = before?.status ?: StepStatus.PENDING,
                    carriedOver = before != null,
                    branch = before?.branch,
                    startedAt = before?.startedAt,
                    finishedAt = before?.finishedAt,
                    input = before?.input,
                    output = before?.output,
                    error = before?.error,
                )
            },
        )

        val opening = if (resumeFrom == null) {
            "${graph.name} started by ${trigger.name.lowercase()}"
        } else {
            "${graph.name} restarted at ${resumeFrom.nodeKey}, carrying what run ${resumeFrom.executionId} produced"
        }
        log.write(executionId, null, LogLevel.INFO, opening)

        // Only what this run is to carry out. A carried-over step has already
        // happened, and handing it to an engine would perform it a second time.
        return ExecutionPlan(execution, recorded.filterNot { it.carriedOver }, graph.edges, earlier?.exits.orEmpty())
    }

    /**
     * What the earlier run leaves to a run starting at [point], or a refusal.
     *
     * Everything here is read out of the record. Re-deriving it - running the
     * earlier steps again to find out what they produced - is the thing being
     * avoided: a step that sent a message or charged a card does not become
     * repeatable because it is being repeated for a good reason.
     */
    private fun earlierRun(point: ResumePoint, graph: WorkflowGraph, order: List<GraphNode>): EarlierRun {
        val earlier = executions.findByIdOrNull(point.executionId)
            ?: throw ExecutionNotFoundException(point.executionId)

        // A run still going is still deciding. Reading half a record and
        // starting a second run against it would leave two runs walking the
        // same graph with the same payload, which is not a re-run of anything.
        if (earlier.status == ExecutionStatus.RUNNING) throw ExecutionStillRunningException(point.executionId)

        val recorded = steps.findByExecutionIdOrderByOrderAsc(point.executionId).associateBy { it.nodeKey }

        val at = order.indexOfFirst { it.key == point.nodeKey }
        if (at < 0) throw StepNotInWorkflowException(point.nodeKey)

        /*
         * Everything before it in the run order, which is what a re-run carries
         * over. Not "everything the chosen node depends on": somebody restarting
         * at step five means steps five onwards, and a parallel path that has
         * already run is part of what the run would have done next.
         */
        val ahead = order.take(at)
        ahead.firstOrNull { it.key !in recorded }?.let {
            // The graph has gained a node since, and it sits ahead of the chosen
            // one. There is no record of what it produced, so the payload this
            // run would carry is missing whatever it contributes.
            throw StepNotInExecutionException(point.executionId, it.key)
        }

        val start = recorded[point.nodeKey] ?: throw StepNotInExecutionException(point.executionId, point.nodeKey)
        if (start.status == StepStatus.PENDING) throw StepNeverRanException(point.nodeKey)

        /*
         * The earlier run's branching, replayed over its own record.
         *
         * The same gate the engines use, told what each step did, which is the
         * only way to reproduce what the earlier run opened up. Deriving it from
         * the statuses instead does not work: a node with no runtime is skipped
         * as well, and its edges were followed.
         */
        val gate = BranchGate(graph.edges)
        val exits = mutableListOf<CarriedExit>()
        for (node in ahead) {
            // Skipped then, and skipped now: nothing that happened leads to it.
            if (!gate.mayRun(node.key)) continue

            val step = recorded.getValue(node.key)
            // A step that failed, or was never reached, opened nothing.
            if (step.status == StepStatus.PENDING || step.status == StepStatus.FAILED) continue

            if (step.branch == null && gate.branches(node.key)) {
                /*
                 * A condition with two ways out that did not say which it took.
                 * Every run recorded before the branch column existed looks like
                 * this, and so does the rare condition that could not be
                 * answered at all. Following both would revive the path the run
                 * refused, and picking one would be a guess, so it refuses.
                 */
                throw BranchNotRecordedException(node.key)
            }
            gate.follow(node.key, step.branch)
            exits += CarriedExit(node.key, step.branch)
        }

        // The chosen node is inside a branch the earlier run did not take.
        // Starting there is asking for the path that did not happen.
        if (!gate.mayRun(point.nodeKey)) throw BranchNotTakenException(point.nodeKey)

        val missing = unresolvable(order[at], start.input, earlier.input)
        if (missing.isNotEmpty()) throw StepInputMissingException(point.nodeKey, missing)

        return EarlierRun(
            taken = ahead.associate { it.key to recorded.getValue(it.key) },
            carried = start.input,
            exits = exits,
        )
    }

    /**
     * The fields the chosen node reads that are not in what it would be handed.
     *
     * A reference names a path into the payload the run is carrying - or into
     * the event that started it, which is what a leading `trigger` means. The
     * payload here is the one the earlier run handed this very node, so a
     * reference that resolved then resolves now; one that does not is a node
     * edited since to read something the earlier run never produced, and running
     * it would quietly substitute nothing at all.
     *
     * Nothing is checked where the payload is not an object, because then there
     * are no fields to check against and refusing would be a guess.
     */
    private fun unresolvable(node: GraphNode, carried: String?, trigger: String?): List<String> {
        val known = properties(carried) ?: return emptyList()
        val readable = known + properties(trigger).orEmpty() + TRIGGER

        return node.mappings.values
            .filter { it.reference }
            .map { it.expression.trim().substringBefore('.') }
            .filter { it.isNotEmpty() }
            .distinct()
            .filterNot { it in readable }
    }

    /** The top-level field names of a payload; null when it is not an object. */
    private fun properties(json: String?): Set<String>? {
        if (json.isNullOrBlank()) return null
        val parsed = runCatching { mapper.readTree(json) as? ObjectNode }.getOrNull() ?: return null
        val names = mutableSetOf<String>()
        parsed.propertyNames().forEach { names += it }
        return names
    }

    /** What one earlier run leaves behind for a run that starts partway down it. */
    private data class EarlierRun(
        /** The steps to copy, by node key: everything ahead of the chosen one. */
        val taken: Map<String, ExecutionStep>,
        /** What the earlier run was holding when it reached the chosen node. */
        val carried: String?,
        val exits: List<CarriedExit>,
    )

    private companion object {
        /** A reference starting with this reads the event, not the payload. */
        const val TRIGGER = "trigger"
    }
}

/**
 * Asked to start from a node the workflow no longer has.
 *
 * The graph a re-run uses is the one the earlier run used, so this means the
 * graph itself has been redrawn since - the published copy republished, or the
 * draft edited - and the node somebody is pointing at is gone.
 */
class StepNotInWorkflowException(nodeKey: String) :
    RuntimeException("The workflow no longer has a step called $nodeKey, so there is nothing to start at")

/** Asked to start from a node the earlier run has no record of. */
class StepNotInExecutionException(executionId: Long, nodeKey: String) :
    RuntimeException(
        "Run $executionId has no record of $nodeKey, so a run starting after it would be missing what it produced",
    )

/** Asked to start from a step the earlier run never reached. */
class StepNeverRanException(nodeKey: String) :
    RuntimeException("$nodeKey never ran, so there is nothing recorded to carry into a run starting there")

/** Asked to start inside a branch the earlier run did not take. */
class BranchNotTakenException(nodeKey: String) : RuntimeException(
    "The earlier run went the other way before $nodeKey, so starting there would revive a path it refused",
)

/** Asked to start below a condition whose answer the earlier run did not keep. */
class BranchNotRecordedException(nodeKey: String) :
    RuntimeException("The earlier run did not record which way $nodeKey went, so where to carry on from is a guess")

/** Asked to start at a step that reads something the earlier run never produced. */
class StepInputMissingException(nodeKey: String, missing: List<String>) :
    RuntimeException("$nodeKey reads ${missing.joinToString()}, which the earlier run did not produce")

/** Asked to re-run part of a run that has not finished. */
class ExecutionStillRunningException(executionId: Long) :
    RuntimeException("Run $executionId has not finished, so what it produced is not yet settled")
