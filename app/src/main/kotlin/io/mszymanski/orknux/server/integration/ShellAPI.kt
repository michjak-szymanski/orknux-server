package io.mszymanski.orknux.server.integration

import io.mszymanski.orknux.connector.shell.ShellInput
import io.mszymanski.orknux.connector.shell.ShellService
import io.mszymanski.orknux.connector.shell.ShellView
import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.stereotype.Controller

/**
 * The shells, for the Shell page.
 *
 * The connection module holds them and knows nothing about who is asking; this
 * decides that and records what was done, which is the division every other
 * admin-level screen on this platform uses.
 *
 * Nothing here can return a private key. There is no field for one on
 * [ShellView] and no query that takes an id and gives back a credential - the
 * only thing the outside is told is whether one is stored. The same is true of
 * the passphrase.
 *
 * Administrators only, and that is not an ordinary permission check. A shell is
 * the one thing on this platform that acts outside it, and somebody who could
 * add one could point this installation's agents at a machine of their
 * choosing.
 */
@Controller
class ShellAPI(
    private val shells: ShellService,
    private val access: WorkspaceAccess,
    private val auditRecorder: WorkspaceAuditRecorder,
) {

    @QueryMapping
    fun shells(): List<ShellView> {
        access.requireAdmin()
        return shells.shells()
    }

    @QueryMapping
    fun shell(@Argument id: Long): ShellView? {
        access.requireAdmin()
        return shells.shell(id)
    }

    @MutationMapping
    fun createShell(@Argument input: ShellInput): ShellView {
        access.requireAdmin()
        val created = shells.create(input)
        auditRecorder.record(
            null,
            WorkspaceAuditCategory.SHELL,
            "Shell ${created.name} added for ${created.username}@${created.host}:${created.port}",
        )
        return created
    }

    @MutationMapping
    fun updateShell(@Argument id: Long, @Argument input: ShellInput): ShellView {
        access.requireAdmin()
        val before = shells.shell(id)
        val updated = shells.update(id, input)

        val message = when {
            before == null || before.name == updated.name -> "Shell ${updated.name} updated"
            else -> "Shell ${before.name} renamed to ${updated.name}"
        }
        auditRecorder.record(null, WorkspaceAuditCategory.SHELL, message)

        // Its own entry, because forgetting a host key is the one edit that
        // makes this installation trust a machine it has never spoken to before.
        if (before?.hostKey != null && updated.hostKey == null) {
            auditRecorder.record(
                null,
                WorkspaceAuditCategory.SHELL,
                "Shell ${updated.name} forgot the host key ${before.hostKey}",
            )
        }
        return updated
    }

    /** The switch on the row. Audited, because turning one on is a grant. */
    @MutationMapping
    fun setShellEnabled(@Argument id: Long, @Argument enabled: Boolean): ShellView {
        access.requireAdmin()
        val updated = shells.setEnabled(id, enabled)
        val what = if (enabled) "turned on" else "turned off"
        auditRecorder.record(null, WorkspaceAuditCategory.SHELL, "Shell ${updated.name} $what")
        return updated
    }

    /**
     * Asks a shell now, rather than waiting for the sweep.
     *
     * Not audited. It is a read - a handshake and a `uname` - and an entry every
     * time somebody presses a button on a page they are already looking at would
     * bury the entries that say something happened.
     */
    @MutationMapping
    fun checkShell(@Argument id: Long): ShellView {
        access.requireAdmin()
        return shells.check(id)
    }

    @MutationMapping
    fun deleteShell(@Argument id: Long): Boolean {
        access.requireAdmin()
        val name = shells.shell(id)?.name ?: return false
        if (!shells.delete(id)) return false

        auditRecorder.record(null, WorkspaceAuditCategory.SHELL, "Shell $name removed")
        return true
    }
}
