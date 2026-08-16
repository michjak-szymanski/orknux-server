package io.mszymanski.orknux.server.workspace

import org.springframework.data.jpa.repository.JpaRepository

interface WorkspaceRepository : JpaRepository<Workspace, Long> {

    fun findByName(name: String): Workspace?
}
