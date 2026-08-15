package io.mszymanski.gyloli.server.agent

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface AgentRepository : JpaRepository<Agent, Long> {

    fun findByTeamId(teamId: Long, pageable: Pageable): Page<Agent>

    fun findByTeamIdAndName(teamId: Long, name: String): Agent?
}
