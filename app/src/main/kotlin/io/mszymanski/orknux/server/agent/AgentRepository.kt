package io.mszymanski.orknux.server.agent

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface AgentRepository : JpaRepository<Agent, Long> {

    fun findByWorkspaceId(workspaceId: Long, pageable: Pageable): Page<Agent>

    fun findByWorkspaceIdAndName(workspaceId: Long, name: String): Agent?

    /** However the name was typed: a model asking for an agent has read it, not copied it. */
    @Query("select a from Agent a where a.workspaceId = :workspaceId and lower(a.name) = lower(:name)")
    fun findNamed(@Param("workspaceId") workspaceId: Long, @Param("name") name: String): Agent?
}
