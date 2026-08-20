package io.mszymanski.orknux.server.attachment

import io.mszymanski.orknux.server.chat.ChatDisabledException
import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Controller

/**
 * The switches that belong to the installation rather than to a workspace.
 *
 * Read by anyone signed in — the chat has to know whether to offer a paperclip —
 * and changed by administrators only. What the configuration file forbids is not
 * offered as a switch at all, which is why [InstallationSettingsView] says
 * whether the screen may ask.
 */
@Controller
class InstallationSettingsAPI(
    private val settings: InstallationSettings,
    private val access: WorkspaceAccess,
    private val auditRecorder: WorkspaceAuditRecorder,
) {

    @QueryMapping
    fun installationSettings(): InstallationSettingsView = InstallationSettingsView(
        attachmentsEnabled = settings.attachmentsEnabled(),
        attachmentsConfigurable = settings.attachmentsConfigurable(),
        attachmentStorage = settings.storage().name,
        // The operator's, and read-only here: a filesystem path is not
        // something to hand a browser the ability to change.
        attachmentLocation = settings.location(),
        attachmentMaxFileSizeMb = settings.maxFileSizeMb().toInt(),
        chatEnabled = settings.chatEnabled(),
        chatConfigurable = settings.chatConfigurable(),
        metricsAnonymous = settings.metricsAnonymous(),
        metricsAnonymousConfigured = settings.metricsAnonymousConfigured(),
    )

    @MutationMapping
    fun setChatEnabled(@Argument enabled: Boolean): InstallationSettingsView {
        access.requireAdmin()
        if (!settings.chatConfigurable()) throw ChatDisabledException()

        settings.setChatEnabled(enabled, currentUser())
        auditRecorder.record(
            null,
            WorkspaceAuditCategory.WORKSPACE,
            if (enabled) "Chat turned on" else "Chat turned off",
        )
        return installationSettings()
    }

    @MutationMapping
    fun setAttachmentsEnabled(@Argument enabled: Boolean): InstallationSettingsView {
        access.requireAdmin()
        if (!settings.attachmentsConfigurable()) throw AttachmentsDisabledException()

        settings.setAttachmentsEnabled(enabled, currentUser())
        auditRecorder.record(
            null,
            WorkspaceAuditCategory.WORKSPACE,
            if (enabled) "Attachments turned on" else "Attachments turned off",
        )
        return installationSettings()
    }

    /**
     * Opens the metrics to anybody who can reach the port, or closes them again.
     *
     * No `configurable` gate, unlike the two above: the file's default for this
     * one is already the closed answer, so a gate would be a way of saying no
     * twice and the switch would never be pressable. What stands in its place is
     * the audit entry — turning this on publishes counters about this
     * installation to whoever can reach it, and that is worth a line with a name
     * against it.
     */
    @MutationMapping
    fun setMetricsAnonymous(@Argument enabled: Boolean): InstallationSettingsView {
        access.requireAdmin()

        settings.setMetricsAnonymous(enabled, currentUser())
        auditRecorder.record(
            null,
            WorkspaceAuditCategory.WORKSPACE,
            if (enabled) {
                "Metrics opened to callers who have not signed in"
            } else {
                "Metrics closed to callers who have not signed in"
            },
        )
        return installationSettings()
    }

    private fun currentUser(): String =
        SecurityContextHolder.getContext().authentication?.name ?: "system"
}

data class InstallationSettingsView(
    /** Whether a chat may carry files, as the file and the screen agree it. */
    val attachmentsEnabled: Boolean,
    /** False when the configuration file has said no, and the switch is not offered. */
    val attachmentsConfigurable: Boolean,
    val attachmentStorage: String,
    /** Where they are written; the operator's setting, shown so it can be checked. */
    val attachmentLocation: String,
    val attachmentMaxFileSizeMb: Int,
    /** Whether this installation has a chat, as the file and the screen agree it. */
    val chatEnabled: Boolean,
    /** False when the configuration file has said no, and the switch is not offered. */
    val chatConfigurable: Boolean,
    /**
     * Whether `/actuator/prometheus` answers a caller who has not signed in.
     *
     * Always switchable, and always off until somebody switches it: this is the
     * one setting here the file does not put a floor under, because the file's
     * default is already the closed answer.
     */
    val metricsAnonymous: Boolean,
    /**
     * What a fresh installation would have answered - ORKNUX_METRICS_ANONYMOUS.
     *
     * Shown so the screen can say when the stored answer differs from the
     * configured one, rather than leaving an operator to wonder why the file
     * they edited appears to be ignored.
     */
    val metricsAnonymousConfigured: Boolean,
)
