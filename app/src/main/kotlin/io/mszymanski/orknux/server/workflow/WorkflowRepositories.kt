package io.mszymanski.orknux.server.workflow

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface WorkflowRepository : JpaRepository<Workflow, Long> {

    fun findByName(name: String): Workflow?
}

interface WorkspaceWorkflowRepository : JpaRepository<WorkspaceWorkflow, Long> {

    fun findByWorkspaceId(workspaceId: Long, pageable: Pageable): Page<WorkspaceWorkflow>

    fun existsByWorkspaceIdAndWorkflowId(workspaceId: Long, workflowId: Long): Boolean
}
