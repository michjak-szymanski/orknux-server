package io.mszymanski.gyloli.connector.connection

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.repository.JpaRepository

interface ConnectionRepository : JpaRepository<Connection, Long> {

    fun findByName(name: String): Connection?

    override fun findAll(pageable: Pageable): Page<Connection>
}

interface TeamConnectionRepository : JpaRepository<TeamConnection, Long> {

    fun findByTeamId(teamId: Long, sort: Sort): List<TeamConnection>

    fun findByTeamIdAndName(teamId: Long, name: String): TeamConnection?

    fun findByConnectionId(connectionId: Long): List<TeamConnection>

    /** Every team's connection to one kind of service, for the listeners. */
    fun findByTypeIn(types: Collection<ConnectionType>): List<TeamConnection>

    fun deleteByTeamId(teamId: Long): Long
}

interface McpServerRepository : JpaRepository<McpServer, Long> {

    fun findByTeamId(teamId: Long, sort: Sort): List<McpServer>

    fun findByTeamIdAndName(teamId: Long, name: String): McpServer?

    fun deleteByTeamId(teamId: Long): Long
}
