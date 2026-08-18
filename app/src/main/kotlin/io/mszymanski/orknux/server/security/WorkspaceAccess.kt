package io.mszymanski.orknux.server.security

import io.mszymanski.orknux.server.workspace.Workspace
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service

/**
 * Decides which workspaces the caller may see.
 *
 * A workspace is opened by the roles assigned to it, and the caller's roles come from
 * whatever the identity provider said about them, translated once by [RoleResolver].
 * Nothing here knows what a directory group is, which is the point: the same check
 * works whichever provider signed somebody in.
 */
@Service
class WorkspaceAccess(
    private val resolver: RoleResolver,
) {

    fun roles(): Set<String> {
        val authentication = SecurityContextHolder.getContext().authentication
        if (authentication == null || !authentication.isAuthenticated || authentication is AnonymousAuthenticationToken) {
            return emptySet()
        }
        return authentication.authorities.mapNotNull(GrantedAuthority::getAuthority).toSet()
    }

    fun isAdmin(): Boolean = resolver.administers(roles())

    /** The roles this caller holds here, whatever the provider called them. */
    fun heldRoles(): Set<Role> = resolver.rolesFor(roles())

    /**
     * Whether the caller may see this workspace.
     *
     * A workspace with no roles is administrators only. That is the same answer the
     * empty group gave before, and it is the safe direction to fail in: a workspace
     * whose audience nobody has decided is not one to show to everybody.
     */
    fun canSee(workspace: Workspace): Boolean {
        if (isAdmin()) return true
        if (workspace.roles.isEmpty()) return false
        val held = heldRoles().mapNotNull { it.id }.toSet()
        return workspace.roles.any { it.id in held }
    }

    fun requireVisible(workspace: Workspace) {
        if (!canSee(workspace)) throw WorkspaceForbiddenException(workspace.name)
    }

    fun requireAdmin() {
        if (!isAdmin()) throw AdminRequiredException()
    }

}

class WorkspaceForbiddenException(name: String) : RuntimeException("You do not have access to workspace \"$name\"")

class AdminRequiredException : RuntimeException("This action requires the administrator role")
