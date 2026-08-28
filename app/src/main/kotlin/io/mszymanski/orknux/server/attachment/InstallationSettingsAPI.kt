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
        revisionRetentionDays = settings.revisionRetentionDays(),
        revisionRetentionDaysConfigured = settings.revisionRetentionDaysConfigured(),
        taskSweepMinutes = settings.taskSweepMinutes(),
        taskSweepMinutesConfigured = settings.taskSweepMinutesConfigured(),
        taskSweepConfigurable = settings.taskSweepConfigurable(),
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

    /**
     * How long a component's history is kept before the sweep takes it.
     *
     * An administrator's, because it is a decision about the disk: the rows are
     * whole copies of function source, tool source and agent prompts, so this
     * number is what decides how large that table gets.
     */
    @MutationMapping
    fun setRevisionRetentionDays(@Argument days: Int): InstallationSettingsView {
        access.requireAdmin()

        settings.setRevisionRetentionDays(days, currentUser())
        auditRecorder.record(
            null,
            WorkspaceAuditCategory.WORKSPACE,
            "Component history kept for $days days",
        )
        return installationSettings()
    }

    /**
     * How long a task may sit queued before something hands it over again.
     *
     * Gated like the chat and attachment switches — what the screen does not
     * offer, this does not store — but the gate itself is in
     * [InstallationSettings.setTaskSweepMinutes] beside the answer it asks,
     * rather than restated here where it would be a second place to remember.
     * A Temporal installation has no field for this: how a stuck task is
     * recovered there is Temporal's business and the interval comes from the
     * file, so a value arriving anyway is refused rather than kept where nobody
     * could see it.
     */
    @MutationMapping
    fun setTaskSweepMinutes(@Argument minutes: Int): InstallationSettingsView {
        access.requireAdmin()

        settings.setTaskSweepMinutes(minutes, currentUser())
        auditRecorder.record(
            null,
            WorkspaceAuditCategory.WORKSPACE,
            "Queued tasks picked up again after $minutes minutes",
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
    /**
     * How many days of component history are kept.
     *
     * A component's versions are what it was before each save - the code and
     * the prompts, in full - so this is the setting that decides how much of
     * the disk they take. Fourteen days unless somebody has said otherwise.
     */
    val revisionRetentionDays: Int,
    /** What a fresh installation would keep - ORKNUX_REVISION_RETENTION_DAYS. */
    val revisionRetentionDaysConfigured: Int,
    /**
     * How many minutes a task may sit queued before something hands it over
     * again.
     *
     * The net under the hand-over: a task whose start was lost - to a process
     * killed at the wrong moment, or a workflow that could not run - would
     * otherwise say QUEUED for ever. Five minutes unless an administrator has
     * said otherwise.
     */
    val taskSweepMinutes: Int,
    /** What a fresh installation would wait - ORKNUX_TASK_SWEEP_MINUTES. */
    val taskSweepMinutesConfigured: Int,
    /**
     * False on an installation running Temporal, and the field is not offered.
     *
     * A `configurable` flag like the chat's and the attachments', and the fact
     * behind it is which engine is carrying tasks. The sweep runs either way;
     * what is not an administrator's decision on Temporal is how long it waits.
     */
    val taskSweepConfigurable: Boolean,
)
