package io.mszymanski.orknux.server.integration

import io.mszymanski.orknux.connector.connection.CreateWorkspaceConnectionInput
import io.mszymanski.orknux.connector.connection.SlackDirectory
import io.mszymanski.orknux.connector.connection.SlackSuggestion
import io.mszymanski.orknux.connector.connection.SlackSuggestions
import io.mszymanski.orknux.connector.connection.SlackTargetCheck
import io.mszymanski.orknux.connector.connection.SlackTargetKind
import io.mszymanski.orknux.connector.connection.WorkspaceConnectionService
import io.mszymanski.orknux.connector.connection.WorkspaceConnectionView
import io.mszymanski.orknux.connector.connection.UpdateWorkspaceConnectionInput
import io.mszymanski.orknux.server.action.MessageTarget
import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.graphql.data.method.annotation.SchemaMapping
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
    private val slackDirectory: SlackDirectory,
) {

    @QueryMapping
    fun workspaceConnections(@Argument workspaceId: Long): List<WorkspaceConnectionView> {
        requireWorkspaceAccess(workspaceId)
        return connections.workspaceConnections(workspaceId)
    }

    @QueryMapping
    fun workspaceConnection(@Argument id: Long): WorkspaceConnectionView? =
        connections.workspaceConnection(id)?.takeIf { access.canSee(it.workspaceId) }

    /**
     * Whether a Slack connection can see the user or channel typed into an
     * action's target field.
     *
     * A query and not a mutation, because it changes nothing and records
     * nothing: the connection's own check is a mutation because it stores what
     * it found and writes an audit entry, and this stores nothing. The shape it
     * follows is `memoryBudget` instead - a form asking a question about
     * something it has not saved yet, and being answered rather than refused.
     *
     * **Nothing here gates a save.** `createAction` and `updateAction` neither
     * call this nor know it exists, and `targetName` stays free text. The
     * answers it can give are wrong about a correct name often enough - a
     * private channel the bot is not in, a member who joined a minute ago, an
     * id from elsewhere - that refusing on them would cost more than the typos
     * it catches. So it advises and the person decides.
     *
     * **The target is a narrowing, not a requirement.** Omitted, both halves of
     * the connection are asked and the answers merged, and the answer says which
     * kind it turned out to be. That is the shape the caller actually has: an
     * action whose target kind has not been set yet could not name one here, so
     * it asked nothing and its panel drew nothing - which is a worse answer than
     * any of the three this can give.
     *
     * Access is the one thing it does refuse on, and for the usual reason: the
     * question is asked of somebody else's connection or of none.
     */
    @QueryMapping
    fun slackTarget(
        @Argument connectionId: Long,
        @Argument target: MessageTarget?,
        @Argument name: String,
    ): SlackTargetCheck {
        requireSlackConnection(connectionId)
        return slackDirectory.check(connectionId, target?.let(::kindOf), name)
    }

    /**
     * The users or channels a Slack connection can see that match what somebody
     * has typed into an action's target field so far.
     *
     * The same question as `slackTarget` asked the other way round, and it
     * answers in the same vocabulary: an outcome and one line, with `UNCHECKED`
     * where there are no suggestions and why. A picker that empties itself with
     * nothing said under it reads as a broken connection, and the commonest
     * reason for it to empty is a bot token carrying no read scope - which is
     * what a token set up to post looks like.
     *
     * **This suggests and never gates.** `targetName` is free text and stays
     * free text: an id pasted out of somebody else's message, a member who
     * joined a minute ago and a private channel this bot is not in are all
     * unsuggestable and all perfectly valid, so the field has to accept what
     * this never offered. `createAction` and `updateAction` do not call it.
     *
     * **The target is a narrowing here too.** Omitted, one list comes back
     * holding both, every row saying which it is, so a picker over a field whose
     * kind is not settled yet has something to draw - and picking a row settles
     * the kind as well as the name.
     *
     * Cheap to call on a keystroke, which is what it is for: the module reads
     * each connection's list once and filters it in memory. A merged question
     * reads the same two per-connection lists rather than a third of its own.
     * Access is the one thing it refuses on.
     */
    @QueryMapping
    fun slackSuggestions(
        @Argument connectionId: Long,
        @Argument target: MessageTarget?,
        @Argument typed: String?,
    ): SlackSuggestions {
        requireSlackConnection(connectionId)
        return slackDirectory.suggest(connectionId, target?.let(::kindOf), typed.orEmpty())
    }

    /**
     * Which of the two a suggested row is, in the vocabulary the caller sends
     * with.
     *
     * The row carries the module's own kind and the schema says `MessageTarget`,
     * because what a picker does with a row is put it in an action - and the
     * value it needs there is the one `createAction` takes. A row that could not
     * say which kind it was would be undrawable in a merged list and unusable
     * after it.
     */
    @SchemaMapping(typeName = "SlackSuggestion", field = "target")
    fun suggestionTarget(suggestion: SlackSuggestion): MessageTarget = targetOf(suggestion.kind)

    /** The same, for the one name a check settled - and null when it settled none. */
    @SchemaMapping(typeName = "SlackTargetCheck", field = "target")
    fun checkTarget(check: SlackTargetCheck): MessageTarget? = check.kind?.let(::targetOf)

    /**
     * Matched by hand rather than by name, so that adding a target the module
     * cannot look up is a compiler error here and not a surprise at runtime. The
     * module holds no notion of an action.
     */
    private fun kindOf(target: MessageTarget) = when (target) {
        MessageTarget.CHANNEL -> SlackTargetKind.CHANNEL
        MessageTarget.USER -> SlackTargetKind.USER
    }

    /** And back, for the same reason. */
    private fun targetOf(kind: SlackTargetKind) = when (kind) {
        SlackTargetKind.CHANNEL -> MessageTarget.CHANNEL
        SlackTargetKind.USER -> MessageTarget.USER
    }

    /** Access, and nothing else: what the connection turns out to be is the module's answer to give. */
    private fun requireSlackConnection(connectionId: Long) {
        connections.workspaceConnection(connectionId)?.takeIf { access.canSee(it.workspaceId) }
            ?: throw ConnectionNotFoundException(connectionId)
    }

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
