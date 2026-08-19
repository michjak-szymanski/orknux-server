package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service

/**
 * Whether a chat is the caller's to read.
 *
 * A chat belongs to the person who started it, so seeing its workspace is not
 * enough. That sentence used to be written once in the chat's queries and again
 * in its stream, and not at all where the files sent with a message are read -
 * which is how the attachments on somebody's private conversation came to be
 * listable and downloadable by everybody else in the workspace. One rule, asked
 * in each of those places, cannot drift the way three copies of it did.
 */
@Service
class ChatOwnership(
    private val workspaces: WorkspaceRepository,
    private val access: WorkspaceAccess,
) {

    /** The caller can see the workspace, and started the chat. */
    fun owns(session: ChatSession): Boolean {
        val workspace = workspaces.findByIdOrNull(session.workspaceId) ?: return false
        return access.canSee(workspace) && session.userId == currentUser()
    }

    /**
     * The same question, as the answer that stops a caller who is not the owner.
     *
     * Somebody else's chat is refused exactly as one that is not there. Saying
     * "that is not yours" confirms that it is somebody's, and which chats a
     * colleague is having is not a fact to hand out - the same reason the MCP
     * surface answers a run in another workspace as a run that does not exist.
     */
    fun requireOwn(session: ChatSession) {
        if (!owns(session)) throw ChatSessionNotFoundException(requireNotNull(session.id))
    }

    private fun currentUser(): String =
        SecurityContextHolder.getContext().authentication?.name ?: "system"
}
