package io.mszymanski.orknux.server.integration

import io.mszymanski.orknux.connector.connection.WorkspaceDirectory
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.springframework.stereotype.Service

/**
 * Workspaces belong to this module, so it is what answers when the connection module
 * asks which ones exist.
 */
@Service
class AppWorkspaceDirectory(private val workspaces: WorkspaceRepository) : WorkspaceDirectory {

    override fun workspaceIds(): List<Long> = workspaces.findAll().mapNotNull { it.id }
}
