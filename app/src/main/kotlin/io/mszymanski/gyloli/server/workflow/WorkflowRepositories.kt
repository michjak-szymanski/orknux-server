package io.mszymanski.gyloli.server.workflow

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface WorkflowRepository : JpaRepository<Workflow, Long> {

    fun findByName(name: String): Workflow?
}

interface TeamWorkflowRepository : JpaRepository<TeamWorkflow, Long> {

    fun findByTeamId(teamId: Long, pageable: Pageable): Page<TeamWorkflow>

    fun existsByTeamIdAndWorkflowId(teamId: Long, workflowId: Long): Boolean
}
