package io.mszymanski.orknux.server.security

import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.springframework.data.repository.findByIdOrNull
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
    private val workspaces: WorkspaceRepository,
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

    /**
     * The same question asked about the workspace an entity says it belongs to.
     *
     * A workspace that is not there and one the caller may not see answer alike,
     * which is what lets a query holding an id return null for both rather than
     * confirming, by refusing, that the id was a real one.
     *
     * A mutation cannot answer null, so it asks this and then throws whatever it
     * already throws for an id that is not there - the same exception with the
     * same words, because a second message would be the difference all over
     * again. Read it as the one question every resolver taking an id asks
     * before it will admit the id exists.
     */
    fun canSee(workspaceId: Long): Boolean = workspaces.findByIdOrNull(workspaceId)?.let { canSee(it) } == true

    fun requireVisible(workspace: Workspace) {
        if (!canSee(workspace)) throw WorkspaceForbiddenException()
    }

    fun requireAdmin() {
        if (!isAdmin()) throw AdminRequiredException()
    }

}

/**
 * Says no without saying what was asked for.
 *
 * It used to name the workspace, which meant that every query taking an id -
 * an action, a condition, an agent, a skill, a tool, a run - had three answers
 * for an arbitrary number, and the middle one handed over the name of the
 * workspace the caller is not in. GraphQL reports errors with a 200, so trying
 * every number in turn is a script rather than an afternoon, and what comes
 * back is a directory of who else is on this installation and what their
 * workspaces are called.
 *
 * The MCP surface has always answered this way and says why: a run in another
 * workspace is answered exactly as one that does not exist, because "that is
 * not yours" confirms it is somebody's. What is left here still says whether
 * an id is real, which is the smaller half of the same leak - closing that one
 * means each of those queries answering null the way `workspace(id)` already
 * does, rather than refusing.
 */
class WorkspaceForbiddenException :
    RuntimeException("That does not exist, or you do not have access to it")

class AdminRequiredException : RuntimeException("This action requires the administrator role")
