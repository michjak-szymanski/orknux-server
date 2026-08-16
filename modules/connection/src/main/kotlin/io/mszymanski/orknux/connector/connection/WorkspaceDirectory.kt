package io.mszymanski.orknux.connector.connection

/**
 * Which workspaces exist. This module holds connections for a workspace but not the workspace
 * itself, so handing a new default to every existing workspace means asking whoever
 * owns them — the interface is what keeps this module from depending on that.
 */
interface WorkspaceDirectory {

    fun workspaceIds(): List<Long>
}
