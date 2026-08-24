package io.mszymanski.orknux.server.workspace

import io.mszymanski.orknux.connector.connection.WorkspaceLifecycleService
import io.mszymanski.orknux.connector.model.ModelService
import io.mszymanski.orknux.server.llm.SessionMemoryBudgets
import io.mszymanski.orknux.server.security.Role
import io.mszymanski.orknux.server.security.RoleNotFoundException
import io.mszymanski.orknux.server.security.RoleRepository
import io.mszymanski.orknux.server.security.WorkspaceAccess
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.graphql.data.method.annotation.SchemaMapping
import org.springframework.stereotype.Controller
import io.mszymanski.orknux.connector.model.ModelKind
import org.springframework.transaction.annotation.Transactional

@Controller
class WorkspaceAPI(
    private val repository: WorkspaceRepository,
    private val roles: RoleRepository,
    private val auditRecorder: WorkspaceAuditRecorder,
    private val access: WorkspaceAccess,
    private val connections: WorkspaceLifecycleService,
    private val models: ModelService,
    private val budgets: SessionMemoryBudgets,
) {

    /**
     * Non-admins only see workspaces whose directory group they belong to. The filter
     * runs in memory because membership lives on the authentication rather than in
     * the database, and an workspace count stays small.
     */
    @QueryMapping
    fun workspaces(@Argument page: Int?, @Argument size: Int?): WorkspacePage {
        val pageable = pageRequest(page, size, Sort.by("name"))
        if (access.isAdmin()) return WorkspacePage(repository.findAll(pageable))

        val visible = repository.findAll(Sort.by("name")).filter(access::canSee)
        return WorkspacePage(PageImpl(visible.page(pageable), pageable, visible.size.toLong()))
    }

    @QueryMapping
    fun workspace(@Argument id: Long): Workspace? = repository.findByIdOrNull(id)?.takeIf(access::canSee)

    @MutationMapping
    @Transactional
    fun createWorkspace(@Argument input: CreateWorkspaceInput): Workspace {
        access.requireAdmin()
        val name = input.name.trim()
        if (name.isEmpty()) throw WorkspaceNameInvalidException()
        if (repository.findByName(name) != null) throw WorkspaceNameTakenException(name)

        val workspace = repository.save(Workspace(name = name, description = input.description?.trim()?.ifEmpty { null }))
        auditRecorder.record(
            workspaceId = requireNotNull(workspace.id),
            operationType = WorkspaceOperationType.ADD,
            newWorkspaceName = workspace.name,
        )
        // The admin default connections come with the workspace.
        provision(requireNotNull(workspace.id), workspace.name)
        return workspace
    }

    private fun provision(workspaceId: Long, name: String) {
        val provisioned = connections.provisionWorkspaceConnections(workspaceId)
        if (provisioned.isEmpty()) return

        val what = if (provisioned.size == 1) "connection" else "connections"
        auditRecorder.record(
            workspaceId,
            WorkspaceAuditCategory.INTEGRATION,
            "${provisioned.size} default $what provisioned for $name",
        )
    }

    /**
     * Backs the workspace settings form: name, description and the role lists.
     *
     * Two callers with two different rights, and the split is the point of the
     * workspace administrator role. Whoever administers *this* workspace may change
     * its name and its description - that is what the role is for, and it is what
     * was asked for. Only an installation administrator may change either role
     * list, and a workspace administrator who sends one is refused unless it is the
     * list that is already there.
     *
     * Refused rather than quietly ignored, and unchanged rather than absent, for
     * the same reason: the settings form loads the lists and posts back what it
     * loaded, so a workspace administrator who touched only the name is sending the
     * current lists and means nothing by it. Somebody who has actually changed one
     * is doing the thing the role does not cover, and being told so beats saving
     * everything except the part they came for.
     *
     * Why they may not: whoever edits the list decides who else gets into the
     * workspace, and can take the role off everybody else - the person who gave it
     * to them included. That is contained to one workspace and may be exactly what
     * an installation wants, but it is a bigger promise than "changing settings",
     * and it is the one that cannot be walked back. Widening this later is a line
     * of code; narrowing it after somebody has arranged their installation around
     * it is taking something away.
     */
    @MutationMapping
    @Transactional
    fun updateWorkspace(@Argument id: Long, @Argument input: UpdateWorkspaceInput): Workspace {
        val newName = input.name.trim()
        if (newName.isEmpty()) throw WorkspaceNameInvalidException()

        val workspace = repository.findByIdOrNull(id) ?: throw WorkspaceNotFoundException(id)
        access.requireAdministers(workspace)
        val previousName = workspace.name
        if (newName != previousName && repository.findByName(newName) != null) {
            throw WorkspaceNameTakenException(newName)
        }

        val previousDescription = workspace.description
        val previousRoles = workspace.roles.mapNotNull { it.id }.toSet()
        val previousAdminRoles = workspace.adminRoles.mapNotNull { it.id }.toSet()

        workspace.name = newName
        workspace.description = input.description?.trim()?.ifEmpty { null }
        /*
         * Null leaves the assignment alone; a list replaces it, empty included —
         * taking every role off a workspace is a decision somebody may make, and it
         * means administrators only.
         */
        val wantedRoles = input.roleIds?.let(::resolve)
        val wantedAdminRoles = input.adminRoleIds?.let(::resolve)

        // The installation administrator's half, checked before anything is
        // assigned: sending the list that is already there is not an edit, so a
        // workspace administrator saving the form they were shown goes through.
        val changesRoles = wantedRoles != null && wantedRoles.mapNotNull { it.id }.toSet() != previousRoles
        val changesAdminRoles =
            wantedAdminRoles != null && wantedAdminRoles.mapNotNull { it.id }.toSet() != previousAdminRoles
        if (changesRoles || changesAdminRoles) access.requireAdmin()

        wantedRoles?.let { workspace.roles = it.toMutableSet() }
        wantedAdminRoles?.let { workspace.adminRoles = it.toMutableSet() }

        /*
         * A role that administers a workspace it cannot open is nothing, so the two
         * lists are checked against each other after both have been applied rather
         * than as each arrives — a save that adds a role and marks it administering
         * in one go is the ordinary case, and checking them one at a time would
         * refuse it depending on the order the fields happened to be read in.
         */
        val opens = workspace.roles.mapNotNull { it.id }.toSet()
        val stranded = workspace.adminRoles.filter { it.id !in opens }
        if (stranded.isNotEmpty()) throw WorkspaceAdminRoleNotAssignedException(stranded.map { it.name }.sorted())

        if (newName != previousName) {
            auditRecorder.record(
                workspaceId = id,
                operationType = WorkspaceOperationType.RENAME,
                oldWorkspaceName = previousName,
                newWorkspaceName = newName,
            )
        }
        val nowRoles = workspace.roles.mapNotNull { it.id }.toSet()
        if (nowRoles != previousRoles) {
            // Named, not counted: who can see a workspace is worth being able to
            // read out of the log a year later.
            val named = workspace.roles.map { it.name }.sorted()
            auditRecorder.record(
                id,
                WorkspaceAuditCategory.WORKSPACE,
                if (named.isEmpty()) {
                    "Workspace roles cleared: administrators only"
                } else {
                    "Workspace roles set to ${named.joinToString(", ")}"
                },
            )
        }
        val nowAdminRoles = workspace.adminRoles.mapNotNull { it.id }.toSet()
        if (nowAdminRoles != previousAdminRoles) {
            // Named for the same reason the list above is, and more so: who may
            // administer a workspace is the line somebody will want to read out of
            // the log a year later, when the question is how it came to be theirs.
            val named = workspace.adminRoles.map { it.name }.sorted()
            auditRecorder.record(
                id,
                WorkspaceAuditCategory.WORKSPACE,
                if (named.isEmpty()) {
                    "Workspace administrators cleared: installation administrators only"
                } else {
                    "Workspace administered by ${named.joinToString(", ")}"
                },
            )
        }
        if (workspace.description != previousDescription) {
            auditRecorder.record(id, WorkspaceAuditCategory.WORKSPACE, "Workspace description updated")
        }
        return workspace
    }

    /** Ids to roles, refusing the whole save on one that names nothing. */
    private fun resolve(ids: List<Long>): List<Role> = ids.distinct()
        .map { roleId -> roles.findByIdOrNull(roleId) ?: throw RoleNotFoundException(roleId) }

    /**
     * Whether the caller administers this workspace, for the interface to paint with.
     *
     * A field on the workspace rather than a flag on the session, because that is
     * what the answer depends on: somebody can lead one workspace and merely work in
     * another, and a single boolean about the person could not say so. It is how the
     * settings page knows whether to offer the name and description at all, and it
     * is true for an installation administrator everywhere.
     */
    @SchemaMapping(typeName = "Workspace")
    fun administered(workspace: Workspace): Boolean = access.canAdminister(workspace)

    /**
     * Chooses the model the workspace uses for its own small jobs.
     *
     * Anyone who can see the workspace may set it: it is a workspace setting,
     * not an administrative one, and the person whose chats get named is the
     * one who cares what names them. Null clears it, which switches those jobs
     * off rather than falling back to something unasked for.
     */
    @MutationMapping
    @Transactional
    fun setWorkspaceCompanionModel(@Argument workspaceId: Long, @Argument modelId: Long?): Workspace {
        val workspace = repository.findByIdOrNull(workspaceId) ?: throw WorkspaceNotFoundException(workspaceId)
        access.requireVisible(workspace)

        val chosen = modelId?.let { models.model(it) ?: throw ModelNotFoundForWorkspaceException(it) }
        if (chosen != null && chosen.workspaceId != workspaceId) throw ModelNotFoundForWorkspaceException(modelId)

        workspace.companionModelId = chosen?.id
        auditRecorder.record(
            workspaceId,
            WorkspaceAuditCategory.MODEL,
            if (chosen == null) "Companion model cleared" else "Companion model set to ${chosen.name}",
        )
        return workspace
    }

    /**
     * Chooses the model the workspace hears with.
     *
     * The same rule as the companion model: whoever can see the workspace may
     * set it, and null switches the microphone off rather than guessing at a
     * substitute. Only a transcription model will do — a chat model handed
     * audio answers something, and what it answers is not a transcript.
     */
    @MutationMapping
    @Transactional
    fun setWorkspaceTranscriptionModel(@Argument workspaceId: Long, @Argument modelId: Long?): Workspace {
        val workspace = repository.findByIdOrNull(workspaceId) ?: throw WorkspaceNotFoundException(workspaceId)
        access.requireVisible(workspace)

        val chosen = modelId?.let { models.model(it) ?: throw ModelNotFoundForWorkspaceException(it) }
        if (chosen != null && chosen.workspaceId != workspaceId) throw ModelNotFoundForWorkspaceException(modelId)
        if (chosen != null && chosen.kind != ModelKind.TRANSCRIPTION) {
            throw ModelNotTranscriptionException(chosen.name)
        }

        workspace.transcriptionModelId = chosen?.id
        auditRecorder.record(
            workspaceId,
            WorkspaceAuditCategory.MODEL,
            if (chosen == null) "Transcription model cleared" else "Transcription model set to ${chosen.name}",
        )
        return workspace
    }

    /**
     * Chooses the model the workspace speaks with.
     *
     * The mirror of the one above, and refused the same way: only a speech model
     * will do, since a chat model handed an answer would talk *about* it rather
     * than read it. Null takes the speaker away.
     */
    @MutationMapping
    @Transactional
    fun setWorkspaceSpeechModel(@Argument workspaceId: Long, @Argument modelId: Long?): Workspace {
        val workspace = repository.findByIdOrNull(workspaceId) ?: throw WorkspaceNotFoundException(workspaceId)
        access.requireVisible(workspace)

        val chosen = modelId?.let { models.model(it) ?: throw ModelNotFoundForWorkspaceException(it) }
        if (chosen != null && chosen.workspaceId != workspaceId) throw ModelNotFoundForWorkspaceException(modelId)
        if (chosen != null && chosen.kind != ModelKind.SPEECH) {
            throw ModelNotSpeechException(chosen.name)
        }

        workspace.speechModelId = chosen?.id
        auditRecorder.record(
            workspaceId,
            WorkspaceAuditCategory.MODEL,
            if (chosen == null) "Speech model cleared" else "Speech model set to ${chosen.name}",
        )
        return workspace
    }

    /**
     * Chooses the model behind the quick chat.
     *
     * A chat model, unlike the two above: this one is asked questions and calls
     * orknux's own tools to answer them, so a model that only listens or only
     * reads aloud would have nothing to do here. Null takes the button away.
     */
    @MutationMapping
    @Transactional
    fun setWorkspaceQuickChatModel(@Argument workspaceId: Long, @Argument modelId: Long?): Workspace {
        val workspace = repository.findByIdOrNull(workspaceId) ?: throw WorkspaceNotFoundException(workspaceId)
        access.requireVisible(workspace)

        val chosen = modelId?.let { models.model(it) ?: throw ModelNotFoundForWorkspaceException(it) }
        if (chosen != null && chosen.workspaceId != workspaceId) throw ModelNotFoundForWorkspaceException(modelId)
        if (chosen != null && chosen.kind != ModelKind.CHAT) throw ModelNotChatException(chosen.name)

        workspace.quickChatModelId = chosen?.id
        auditRecorder.record(
            workspaceId,
            WorkspaceAuditCategory.MODEL,
            if (chosen == null) "Quick chat model cleared" else "Quick chat model set to ${chosen.name}",
        )
        return workspace
    }

    /**
     * Whether the quick chat may change things, or only look at them.
     *
     * Recorded either way: this is the setting that decides whether a panel
     * somebody opened to ask a question can act on the workspace, and "who
     * turned that on" is a question worth being able to answer afterwards.
     */
    @MutationMapping
    @Transactional
    fun setWorkspaceQuickChatWrites(@Argument workspaceId: Long, @Argument allowed: Boolean): Workspace {
        val workspace = repository.findByIdOrNull(workspaceId) ?: throw WorkspaceNotFoundException(workspaceId)
        access.requireVisible(workspace)

        workspace.quickChatMayWrite = allowed
        auditRecorder.record(
            workspaceId,
            WorkspaceAuditCategory.MODEL,
            if (allowed) "Quick chat allowed to make changes" else "Quick chat limited to reading",
        )
        return workspace
    }

    /**
     * What agents in this workspace are given when they ask for nothing.
     *
     * The middle step of three - the agent's own share, then this, then the
     * built-in allowance - and it exists because the per-agent setting is the
     * right place to make an exception and the wrong place to state a policy.
     * An installation that has decided its agents should remember more than the
     * built-in allowance was saying so once per agent, again on every agent
     * made afterwards, and could only read the decision back by looking at
     * every row.
     *
     * Null clears it, which puts every agent that sets nothing back on the
     * built-in allowance rather than leaving them on the last value; that is
     * the same rule the agent's own share follows, and it is what lets a form
     * offer "the default" as a thing to choose.
     *
     * Only the bounds are checked, and the sentence is the one the agent form
     * raises because it comes from the same calculation. What is *not* checked
     * is everything that needs a model - a window too small to carry an
     * exchange, a model reserving most of its window for its answer - because
     * this default is not tied to a model. A workspace runs several at once
     * whose windows differ by an order of magnitude, so refusing a default
     * because the smallest of them could not give it would refuse a setting
     * that is right for every other model in the workspace. Those refusals
     * still happen, in `SessionMemoryBudgets`, against the model an agent
     * actually uses, at the point its budget is worked out.
     *
     * Whoever can see the workspace may set it, like the model settings above
     * and unlike the roles: it is a workspace setting rather than an
     * administrative one, and it is recorded either way.
     */
    @MutationMapping
    @Transactional
    fun setWorkspaceDefaultMemoryShare(@Argument workspaceId: Long, @Argument share: Int?): Workspace {
        val workspace = repository.findByIdOrNull(workspaceId) ?: throw WorkspaceNotFoundException(workspaceId)
        access.requireVisible(workspace)

        budgets.resolveDefault(share).refusal?.let { throw WorkspaceMemoryShareUnusableException(it) }

        workspace.defaultMemoryShare = share
        auditRecorder.record(
            workspaceId,
            WorkspaceAuditCategory.MODEL,
            share?.let { "Agents default to $it% of their model's context window" }
                ?: "Agent memory default cleared",
        )
        return workspace
    }

    /**
     * How voice mode decides somebody has finished talking, for this workspace.
     *
     * Three settings and one call, because they are one decision. The pause is
     * what ends a turn, the ratio decides who counts as talking while it is
     * running, and the fuse is what happens when no pause ever comes; changing
     * one without seeing the other two is how a workspace ends up with a
     * generous pause that a ratio never lets it reach.
     *
     * All three are stated on every call and null clears one, which puts it
     * back on voice mode's own value rather than leaving it on whatever was set
     * last - the same rule `setWorkspaceDefaultMemoryShare` follows, and what
     * lets a form offer "the default" as a thing to choose. Nothing here states
     * what those values are: they belong to the interface, and a copy on this
     * side would be a second source of truth that drifts the first time either
     * moves. Null travels to the client as null and the client supplies its
     * own.
     *
     * Only the bounds are checked, and each refusal names what is allowed
     * rather than saying no - see [VoiceTurnTaking] for why each bound is where
     * it is. Whoever can see the workspace may set it, like the model settings
     * above and unlike the roles: it is a workspace setting rather than an
     * administrative one, and it is recorded either way.
     */
    @MutationMapping
    @Transactional
    fun setWorkspaceVoiceTurnTaking(
        @Argument workspaceId: Long,
        @Argument pauseEndsTurnMs: Int?,
        @Argument speechOverRoomPercent: Int?,
        @Argument unattendedMicrophoneMs: Int?,
    ): Workspace {
        val workspace = repository.findByIdOrNull(workspaceId) ?: throw WorkspaceNotFoundException(workspaceId)
        access.requireVisible(workspace)

        VoiceTurnTaking.refusalFor(pauseEndsTurnMs, speechOverRoomPercent, unattendedMicrophoneMs)
            ?.let { throw WorkspaceVoiceTurnTakingUnusableException(it) }

        workspace.voicePauseEndsTurnMs = pauseEndsTurnMs
        workspace.voiceSpeechOverRoomPercent = speechOverRoomPercent
        workspace.voiceUnattendedMicrophoneMs = unattendedMicrophoneMs

        auditRecorder.record(
            workspaceId,
            // What happens inside a chat: the microphone is a chat's, not a model's.
            WorkspaceAuditCategory.CHAT,
            VoiceTurnTaking.audit(pauseEndsTurnMs, speechOverRoomPercent, unattendedMicrophoneMs),
        )
        return workspace
    }

    /**
     * Where an answer is cut for the speech provider, for this workspace.
     *
     * Its own call rather than a fourth argument on the one above, although the
     * form draws both on one card and saves them with one press. Turn-taking is
     * three numbers that are one decision - a pause a sensitivity never lets the
     * microphone reach is not a setting anybody meant to make - and this is not
     * part of that decision: it is about the half of a turn the model is
     * talking, and nothing about it can contradict any of those three.
     *
     * Nothing to check and nothing to refuse. The three values are the whole of
     * what may be asked for, and the schema is what says so - an enum arriving
     * off the wire has already been one of them or the request never reached
     * here. Whoever can see the workspace may set it, like the settings above.
     */
    @MutationMapping
    @Transactional
    fun setWorkspaceVoiceSpeechChunking(
        @Argument workspaceId: Long,
        @Argument chunking: SpeechChunking,
    ): Workspace {
        val workspace = repository.findByIdOrNull(workspaceId) ?: throw WorkspaceNotFoundException(workspaceId)
        access.requireVisible(workspace)

        workspace.voiceSpeechChunking = chunking

        auditRecorder.record(
            workspaceId,
            // The same category as turn-taking: what a chat does, not what a
            // model is.
            WorkspaceAuditCategory.CHAT,
            when (chunking) {
                SpeechChunking.NONE -> "Answers are read aloud in one piece"
                SpeechChunking.SENTENCE -> "Answers are read aloud a sentence at a time"
                SpeechChunking.PARAGRAPH -> "Answers are read aloud a paragraph at a time"
            },
        )
        return workspace
    }

    @MutationMapping
    @Transactional
    fun deleteWorkspace(@Argument id: Long): Boolean {
        access.requireAdmin()
        val workspace = repository.findByIdOrNull(id) ?: return false
        repository.delete(workspace)
        // workspace_connection has no foreign key to workspace — the module owns its own
        // tables — so what was held for this workspace is dropped explicitly.
        connections.forgetWorkspace(id)
        auditRecorder.record(
            workspaceId = id,
            operationType = WorkspaceOperationType.REMOVE,
            oldWorkspaceName = workspace.name,
        )
        return true
    }
}

data class CreateWorkspaceInput(
    val name: String,
    val description: String? = null,
)

data class UpdateWorkspaceInput(
    val name: String,
    val description: String? = null,
    /** The roles that open this workspace. Null leaves them alone; empty means administrators only. */
    val roleIds: List<Long>? = null,
    /**
     * The roles that also administer it, which has to be a subset of [roleIds].
     *
     * Null leaves them alone; empty means installation administrators only, which is
     * what every workspace has until somebody decides otherwise. Only an installation
     * administrator may change this - a workspace administrator sending back the list
     * they were shown is not changing it and is not refused.
     */
    val adminRoleIds: List<Long>? = null,
)

data class WorkspacePage(
    val content: List<Workspace>,
    val page: Int,
    val size: Int,
    val totalElements: Int,
    val totalPages: Int,
) {
    constructor(page: Page<Workspace>) : this(
        content = page.content,
        page = page.number,
        size = page.size,
        totalElements = page.totalElements.toInt(),
        totalPages = page.totalPages,
    )
}

class WorkspaceNotFoundException(id: Long) : RuntimeException("No workspace with id $id")

class WorkspaceNameTakenException(name: String) : RuntimeException("A workspace named \"$name\" already exists")

class WorkspaceNameInvalidException : RuntimeException("A workspace name is required")

/**
 * A role was told to administer a workspace it is not assigned to.
 *
 * Refused rather than assigned quietly, because what it would produce is a role
 * that administers a workspace nobody holding it can open - a permission that
 * looks granted on the settings page and does nothing at all. The sentence names
 * the roles, since the fix is to add them above and save again.
 */
class WorkspaceAdminRoleNotAssignedException(names: List<String>) : RuntimeException(
    "${names.joinToString(", ")} cannot administer this workspace without being assigned to it. " +
        "Add them to the roles that open it, then mark them as administering.",
)

/** A model chosen for a workspace has to be one of that workspace's own. */
class ModelNotTranscriptionException(name: String) : RuntimeException(
    "$name is not a transcription model. A microphone needs one that turns speech into text; " +
        "add one under Models with the transcription kind.",
)

class ModelNotChatException(name: String) : RuntimeException(
    "$name is not a chat model. The quick chat asks questions and calls tools to answer them, " +
        "which is something only a chat model does.",
)

class ModelNotSpeechException(name: String) : RuntimeException(
    "$name is not a speech model. Reading an answer aloud needs one that turns text into speech; " +
        "add one under Models with the speech kind.",
)

class ModelNotFoundForWorkspaceException(id: Long) :
    RuntimeException("No model with id $id in this workspace")

/**
 * A default share of a context window that could not work, refused where it was set.
 *
 * Carries the sentence `SessionMemoryBudgets` wrote, which is the same sentence
 * the agent form is refused with, from the same check - two surfaces set the
 * same setting and must not come to disagree about which numbers are allowed.
 */
class WorkspaceMemoryShareUnusableException(message: String) : RuntimeException(message)

/**
 * The bounds voice mode's turn-taking settings are held to, and the reasons.
 *
 * Bounds only, and nothing that resembles a default: what a workspace that has
 * decided nothing gets belongs to the interface, which is the half that can
 * judge it, and every number named here is a limit rather than a value anybody
 * is given. The units are the interface's own too, so nothing converts at the
 * boundary - the other place a second source of truth hides.
 */
object VoiceTurnTaking {

    /**
     * A pause between one and a half and ten seconds.
     *
     * The floor sits strictly above 1.2 seconds, which is the value that was
     * demonstrated to cut people off at clause breaks: people stop to think in
     * the middle of a sentence and every one of those stops was read as their
     * turn ending. Putting the floor above it is what stops the reported bug
     * from being reproducible through configuration.
     *
     * Ten seconds is the ceiling because past it nothing happening no longer
     * reads as the application being patient; it reads as the application being
     * broken, and somebody starts talking again to see whether it is alive.
     */
    val pauseEndsTurnMs = 1_500..10_000

    /**
     * A voice between 1.2 and 6 times the room, as a percentage of it.
     *
     * Below about 1.2 the ratio cannot separate a voice from the room it is
     * spoken in, and the failure inverts: a breath or a keystroke clears the
     * line, holds the turn open, and the turn never ends at all. Above 6 you
     * have to raise your voice to be heard, which is the complaint this setting
     * exists to answer in the first place.
     */
    val speechOverRoomPercent = 120..600

    /**
     * An unattended microphone between five minutes and an hour.
     *
     * Thirty seconds and two minutes both looked like defensible limits on a
     * turn and both cut the same person off mid-sentence, so the floor has to
     * be well clear of a long spoken thought rather than merely above whatever
     * failed last. It is not a limit on how much anybody may say: it fires only
     * where no pause occurred in the whole span, which is extraordinary for a
     * person and ordinary for a microphone left open in an empty room.
     *
     * An hour is the ceiling because a fuse that never blows is not a fuse.
     */
    val unattendedMicrophoneMs = 300_000..3_600_000

    /**
     * The first of the three that is out of bounds, said as a sentence.
     *
     * Each names what is allowed rather than reporting that something was
     * refused, because "out of range" tells whoever typed it nothing about what
     * to type instead.
     */
    fun refusalFor(pauseMs: Int?, speechPercent: Int?, unattendedMs: Int?): String? = when {
        pauseMs != null && pauseMs !in pauseEndsTurnMs ->
            "A pause that ends a turn has to be between 1.5 and 10 seconds " +
                "(${pauseEndsTurnMs.first} to ${pauseEndsTurnMs.last} ms). " +
                "Below that it lands in the middle of a sentence; above it the microphone looks broken."

        speechPercent != null && speechPercent !in speechOverRoomPercent ->
            "A voice has to stand between ${speechOverRoomPercent.first}% and ${speechOverRoomPercent.last}% " +
                "of the room to be heard as one. Below that a breath holds the turn open and it never ends; " +
                "above it you have to raise your voice."

        unattendedMs != null && unattendedMs !in unattendedMicrophoneMs ->
            "An unattended microphone has to give up between 5 minutes and an hour " +
                "(${unattendedMicrophoneMs.first} to ${unattendedMicrophoneMs.last} ms). " +
                "It is a fuse for a microphone nobody is at, not a limit on how long anybody may talk."

        else -> null
    }

    /** One line for the log, whichever of the three were set and whichever were cleared. */
    fun audit(pauseMs: Int?, speechPercent: Int?, unattendedMs: Int?): String =
        if (pauseMs == null && speechPercent == null && unattendedMs == null) {
            "Voice turn-taking back to the default"
        } else {
            "Voice turn-taking set to a pause of ${pauseMs?.let { "$it ms" } ?: "the default"}, " +
                "a voice at ${speechPercent?.let { "$it%" } ?: "the default"} of the room and " +
                "an unattended microphone of ${unattendedMs?.let { "$it ms" } ?: "the default"}"
        }
}

/**
 * A voice turn-taking setting outside its bounds, refused where it was set.
 *
 * Refused rather than clamped, because what a clamp produces is a form that
 * saves a number nobody typed and a microphone that behaves in a way the
 * settings page does not describe. The sentence names what is allowed instead.
 */
class WorkspaceVoiceTurnTakingUnusableException(message: String) : RuntimeException(message)
