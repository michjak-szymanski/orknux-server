package io.mszymanski.orknux.server.integration

import io.mszymanski.orknux.connector.connection.CreateWorkspaceConnectionInput
import io.mszymanski.orknux.connector.connection.WorkspaceConnectionService
import io.mszymanski.orknux.connector.connection.WorkspaceConnectionView
import io.mszymanski.orknux.connector.connection.UpdateWorkspaceConnectionInput
import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import io.mszymanski.orknux.server.workspace.WorkspaceNotFoundException
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.stereotype.Controller

/**
 * The connections one workspace holds. The connection module holds them and the
 * credentials; this checks the caller may see the workspace and records what they
 * did — including that a credential was revealed, which is a person's action.
 */
@Controller
class WorkspaceConnectionAPI(
    private val connections: WorkspaceConnectionService,
    private val workspaces: WorkspaceRepository,
    private val access: WorkspaceAccess,
    private val auditRecorder: WorkspaceAuditRecorder,
) {

    @QueryMapping
    fun workspaceConnections(@Argument workspaceId: Long): List<WorkspaceConnectionView> {
        requireWorkspaceAccess(workspaceId)
        return connections.workspaceConnections(workspaceId)
    }

    @QueryMapping
    fun workspaceConnection(@Argument id: Long): WorkspaceConnectionView? {
        val connection = connections.workspaceConnection(id) ?: return null
        requireWorkspaceAccess(connection.workspaceId)
        return connection
    }

    @MutationMapping
    fun createWorkspaceConnection(@Argument input: CreateWorkspaceConnectionInput): WorkspaceConnectionView {
        requireWorkspaceAccess(input.workspaceId)
        val created = connections.createWorkspaceConnection(input)
        auditRecorder.record(input.workspaceId, WorkspaceAuditCategory.INTEGRATION, "Connection ${created.name} added")
        return created
    }

    /**
     * Backs the connection settings form. An inherited connection keeps the
     * default's name, type and URL; everything else is the workspace's to set.
     */
    @MutationMapping
    fun updateWorkspaceConnection(@Argument id: Long, @Argument input: UpdateWorkspaceConnectionInput): WorkspaceConnectionView {
        val connection = connections.workspaceConnection(id) ?: throw ConnectionNotFoundException(id)
        requireWorkspaceAccess(connection.workspaceId)

        val updated = connections.updateWorkspaceConnection(id, input)
        auditRecorder.record(
            connection.workspaceId,
            WorkspaceAuditCategory.INTEGRATION,
            "Connection ${updated.name} settings updated",
        )
        return updated
    }

    /**
     * Clears the workspace's credentials. A connection the workspace added itself has
     * nothing to fall back on, so it goes; an inherited one returns to the
     * admin default.
     */
    @MutationMapping
    fun disconnectWorkspaceConnection(@Argument id: Long): Boolean {
        val connection = connections.workspaceConnection(id) ?: return false
        requireWorkspaceAccess(connection.workspaceId)
        if (!connections.disconnectWorkspaceConnection(id)) return false

        auditRecorder.record(
            connection.workspaceId,
            WorkspaceAuditCategory.INTEGRATION,
            "Connection ${connection.name} disconnected",
        )
        return true
    }

    /** Calls the service and keeps what came back, which is what status reports. */
    @MutationMapping
    fun testWorkspaceConnection(@Argument id: Long): WorkspaceConnectionView {
        val connection = connections.workspaceConnection(id) ?: throw ConnectionNotFoundException(id)
        requireWorkspaceAccess(connection.workspaceId)

        val checked = connections.testWorkspaceConnection(id)
        auditRecorder.record(
            checked.workspaceId,
            WorkspaceAuditCategory.INTEGRATION,
            "Connection ${checked.name} checked: ${checked.status.name.lowercase().replace('_', ' ')}",
        )
        return checked
    }

    /** Hands the stored credentials to the settings form behind the "Reveal" action. */
    @MutationMapping
    fun revealWorkspaceConnectionSecret(@Argument id: Long): String? {
        val connection = connections.workspaceConnection(id) ?: throw ConnectionNotFoundException(id)
        requireWorkspaceAccess(connection.workspaceId)

        auditRecorder.record(
            connection.workspaceId,
            WorkspaceAuditCategory.INTEGRATION,
            "Credentials for ${connection.name} revealed",
        )
        return connections.revealWorkspaceConnectionSecret(id)
    }

    private fun requireWorkspaceAccess(workspaceId: Long) {
        val workspace = workspaces.findByIdOrNull(workspaceId) ?: throw WorkspaceNotFoundException(workspaceId)
        access.requireVisible(workspace)
    }
}

class ConnectionNotFoundException(id: Long) : RuntimeException("No connection with id $id")
