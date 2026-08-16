package io.mszymanski.orknux.connector.connection

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.repository.JpaRepository

interface ConnectionRepository : JpaRepository<Connection, Long> {

    fun findByName(name: String): Connection?

    override fun findAll(pageable: Pageable): Page<Connection>
}

interface WorkspaceConnectionRepository : JpaRepository<WorkspaceConnection, Long> {

    fun findByWorkspaceId(workspaceId: Long, sort: Sort): List<WorkspaceConnection>

    fun findByWorkspaceIdAndName(workspaceId: Long, name: String): WorkspaceConnection?

    fun findByConnectionId(connectionId: Long): List<WorkspaceConnection>

    /** Every workspace's connection to one kind of service, for the listeners. */
    fun findByTypeIn(types: Collection<ConnectionType>): List<WorkspaceConnection>

    fun deleteByWorkspaceId(workspaceId: Long): Long
}

interface McpServerRepository : JpaRepository<McpServer, Long> {

    fun findByWorkspaceId(workspaceId: Long, sort: Sort): List<McpServer>

    fun findByWorkspaceIdAndName(workspaceId: Long, name: String): McpServer?

    fun deleteByWorkspaceId(workspaceId: Long): Long
}
