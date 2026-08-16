package io.mszymanski.orknux.server.workspace

import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import java.time.OffsetDateTime

/**
 * Writes the audit trail. The user id is the LDAP uid of the authenticated
 * caller, so every entry is attributable.
 */
@Service
class WorkspaceAuditRecorder(
    private val repository: WorkspaceAuditRepository,
) {

    /** Workspace lifecycle: keeps the names either side so the org view can read them. */
    fun record(
        workspaceId: Long,
        operationType: WorkspaceOperationType,
        oldWorkspaceName: String? = null,
        newWorkspaceName: String? = null,
    ): WorkspaceAudit = repository.save(
        WorkspaceAudit(
            workspaceId = workspaceId,
            category = WorkspaceAuditCategory.WORKSPACE,
            message = workspaceMessage(operationType, oldWorkspaceName, newWorkspaceName),
            oldWorkspaceName = oldWorkspaceName,
            newWorkspaceName = newWorkspaceName,
            operationType = operationType,
            date = OffsetDateTime.now(),
            userId = currentUserId(),
        ),
    )

    /**
     * Anything else that happened, already worded for display. A null [workspaceId]
     * records an admin-level change, which only the admin audit log shows.
     */
    fun record(workspaceId: Long?, category: WorkspaceAuditCategory, message: String): WorkspaceAudit = repository.save(
        WorkspaceAudit(
            workspaceId = workspaceId,
            category = category,
            message = message,
            date = OffsetDateTime.now(),
            userId = currentUserId(),
        ),
    )

    /**
     * Something the system did with nobody asking: an event arriving on a
     * connection and starting a workflow. [actor] stands where a user id
     * normally does, so the log says what set it off instead of naming a person
     * who was not there.
     */
    fun recordAutomated(
        workspaceId: Long?,
        category: WorkspaceAuditCategory,
        message: String,
        actor: String,
    ): WorkspaceAudit = repository.save(
        WorkspaceAudit(
            workspaceId = workspaceId,
            category = category,
            message = message,
            date = OffsetDateTime.now(),
            userId = actor,
        ),
    )

    private fun workspaceMessage(
        operationType: WorkspaceOperationType,
        oldWorkspaceName: String?,
        newWorkspaceName: String?,
    ): String = when (operationType) {
        WorkspaceOperationType.ADD -> "Workspace $newWorkspaceName created"
        WorkspaceOperationType.RENAME -> "Workspace $oldWorkspaceName renamed to $newWorkspaceName"
        WorkspaceOperationType.REMOVE -> "Workspace $oldWorkspaceName deleted"
    }

    private fun currentUserId(): String {
        val authentication = SecurityContextHolder.getContext().authentication
        check(
            authentication != null &&
                authentication.isAuthenticated &&
                authentication !is AnonymousAuthenticationToken,
        ) {
            "No authenticated user to attribute this change to"
        }
        return authentication.name
    }
}
