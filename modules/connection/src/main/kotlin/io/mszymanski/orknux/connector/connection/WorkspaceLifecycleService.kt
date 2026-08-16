package io.mszymanski.orknux.connector.connection

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * What orknux-server tells the connector about the lifetime of a workspace.
 *
 * Workspaces live in the server's database, so there is no foreign key from
 * `workspace_connection` to them and no cascade behind a delete. Both ends of a
 * workspace's life have to be reported, or the connector keeps rows — and
 * credentials — for a workspace that no longer exists.
 */
@Service
class WorkspaceLifecycleService(
    private val provisioning: ConnectionProvisioning,
    private val workspaceConnections: WorkspaceConnectionRepository,
    private val mcpServers: McpServerRepository,
) {

    /** Called when a workspace is created. Returns the copies it was given. */
    @Transactional
    fun provisionWorkspaceConnections(workspaceId: Long): List<WorkspaceConnectionView> =
        provisioning.provisionDefaults(workspaceId).map(::WorkspaceConnectionView)

    /**
     * Called when a workspace is deleted. Returns how many rows went, so the caller
     * can say so; running it for an unknown workspace is a no-op, which makes it
     * safe to retry.
     */
    @Transactional
    fun forgetWorkspace(workspaceId: Long): Int =
        (workspaceConnections.deleteByWorkspaceId(workspaceId) + mcpServers.deleteByWorkspaceId(workspaceId)).toInt()
}
