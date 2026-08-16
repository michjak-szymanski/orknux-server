package io.mszymanski.orknux.workflow.execution

import org.springframework.data.jpa.domain.Specification
import java.time.OffsetDateTime
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor

interface WorkflowExecutionRepository :
    JpaRepository<WorkflowExecution, Long>,
    JpaSpecificationExecutor<WorkflowExecution> {

    /** The most recent run of one workflow, for the list that shows where it got to. */
    fun findFirstByWorkspaceIdAndWorkflowIdOrderByStartedAtDesc(
        workspaceId: Long,
        workflowId: Long,
    ): WorkflowExecution?
}

interface ExecutionStepRepository : JpaRepository<ExecutionStep, Long> {
    fun findByExecutionIdOrderByOrderAsc(executionId: Long): List<ExecutionStep>
    fun findByExecutionIdAndNodeKey(executionId: Long, nodeKey: String): ExecutionStep?
}

interface ExecutionLogRepository : JpaRepository<ExecutionLog, Long> {
    fun findByExecutionIdOrderBySequenceAsc(executionId: Long): List<ExecutionLog>

    /** The next line's sequence number; see [RunLogger] for why it is read, not counted. */
    fun countByExecutionId(executionId: Long): Int
}

/**
 * Filters for the runs listing; the optional ones are simply left out. A
 * `Specification` rather than JPQL, because `:enum IS NULL OR …` fails in
 * Hibernate 6.
 */
fun executionFilter(
    workspaceId: Long?,
    workflowId: Long?,
    status: ExecutionStatus?,
    since: OffsetDateTime? = null,
    search: String? = null,
): Specification<WorkflowExecution> = Specification { root, _, builder ->
    val predicates = mutableListOf<jakarta.persistence.criteria.Predicate>()

    workspaceId?.let { predicates += builder.equal(root.get<Long>("workspaceId"), it) }
    workflowId?.let { predicates += builder.equal(root.get<Long>("workflowId"), it) }
    status?.let { predicates += builder.equal(root.get<ExecutionStatus>("status"), it) }
    since?.let { predicates += builder.greaterThanOrEqualTo(root.get("startedAt"), it) }
    // The name the workflow had when the run started is the only text a run
    // carries, and it is what the executions view searches.
    search?.let {
        predicates += builder.like(builder.lower(root.get("workflowName")), "%${it.lowercase()}%")
    }

    builder.and(*predicates.toTypedArray())
}
