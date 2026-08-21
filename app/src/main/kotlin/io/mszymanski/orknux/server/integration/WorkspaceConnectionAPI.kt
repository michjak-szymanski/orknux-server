package io.mszymanski.orknux.server.integration

import io.mszymanski.orknux.connector.connection.CreateWorkspaceConnectionInput
import io.mszymanski.orknux.connector.connection.WorkspaceConnectionService
import io.mszymanski.orknux.connector.connection.WorkspaceConnectionView
import io.mszymanski.orknux.connector.connection.UpdateWorkspaceConnectionInput
import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
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
    fun workspaceConnection(@Argument id: Long): WorkspaceConnectionView? =
        connections.workspaceConnection(id)?.takeIf { access.canSee(it.workspaceId) }

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
        val connection = connections.workspaceConnection(id)?.takeIf { access.canSee(it.workspaceId) }
            ?: throw ConnectionNotFoundException(id)

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
        val connection = connections.workspaceConnection(id)?.takeIf { access.canSee(it.workspaceId) } ?: return false
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
        val connection = connections.workspaceConnection(id)?.takeIf { access.canSee(it.workspaceId) }
            ?: throw ConnectionNotFoundException(id)

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
        val connection = connections.workspaceConnection(id)?.takeIf { access.canSee(it.workspaceId) }
            ?: throw ConnectionNotFoundException(id)

        auditRecorder.record(
            connection.workspaceId,
            WorkspaceAuditCategory.INTEGRATION,
            "Credentials for ${connection.name} revealed",
        )
        return connections.revealWorkspaceConnectionSecret(id)
    }

    /**
     * The same for the app-level token, which had no way back out at all.
     *
     * The entry names the credential rather than saying "Credentials", because
     * there are two now and a log that cannot tell them apart answers neither
     * question anybody asks it. The bot token's entry keeps its own wording, so
     * the lines already in the table go on meaning exactly what they meant.
     */
    @MutationMapping
    fun revealWorkspaceConnectionAppToken(@Argument id: Long): String? {
        val connection = connections.workspaceConnection(id)?.takeIf { access.canSee(it.workspaceId) }
            ?: throw ConnectionNotFoundException(id)

        auditRecorder.record(
            connection.workspaceId,
            WorkspaceAuditCategory.INTEGRATION,
            "App-level token for ${connection.name} revealed",
        )
        return connections.revealWorkspaceConnectionAppToken(id)
    }

    private fun requireWorkspaceAccess(workspaceId: Long) {
        access.requireVisible(workspaceId)
    }
}

class ConnectionNotFoundException(id: Long) : RuntimeException("No connection with id $id")
