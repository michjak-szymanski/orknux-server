package io.mszymanski.orknux.server.workflow

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface WorkflowRepository : JpaRepository<Workflow, Long> {

    fun findByName(name: String): Workflow?
}

interface WorkspaceWorkflowRepository : JpaRepository<WorkspaceWorkflow, Long> {

    fun findByWorkspaceId(workspaceId: Long, pageable: Pageable): Page<WorkspaceWorkflow>

    /**
     * All of them, for a caller asking about the workspace rather than showing
     * it a page - which in practice means "does anything here still name this
     * definition", asked once when somebody tries to delete one.
     */
    fun findByWorkspaceId(workspaceId: Long): List<WorkspaceWorkflow>

    fun existsByWorkspaceIdAndWorkflowId(workspaceId: Long, workflowId: Long): Boolean

    /**
     * The assignment itself, for a caller that needs more than whether it is
     * there - which in practice means whether the workspace has it switched on.
     */
    fun findByWorkspaceIdAndWorkflowId(workspaceId: Long, workflowId: Long): WorkspaceWorkflow?
}
