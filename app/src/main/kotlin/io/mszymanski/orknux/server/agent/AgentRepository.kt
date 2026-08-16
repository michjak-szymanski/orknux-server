package io.mszymanski.orknux.server.agent

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface AgentRepository : JpaRepository<Agent, Long> {

    fun findByWorkspaceId(workspaceId: Long, pageable: Pageable): Page<Agent>

    fun findByWorkspaceIdAndName(workspaceId: Long, name: String): Agent?
}
