package io.mszymanski.orknux.server.workspace

import io.mszymanski.orknux.connector.connection.WorkspaceLifecycleService
import io.mszymanski.orknux.connector.model.ModelService
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

    /** Backs the workspace settings form: name, description and directory group in one save. */
    @MutationMapping
    @Transactional
    fun updateWorkspace(@Argument id: Long, @Argument input: UpdateWorkspaceInput): Workspace {
        access.requireAdmin()
        val newName = input.name.trim()
        if (newName.isEmpty()) throw WorkspaceNameInvalidException()

        val workspace = repository.findByIdOrNull(id) ?: throw WorkspaceNotFoundException(id)
        val previousName = workspace.name
        if (newName != previousName && repository.findByName(newName) != null) {
            throw WorkspaceNameTakenException(newName)
        }

        val previousDescription = workspace.description
        val previousRoles = workspace.roles.mapNotNull { it.id }.toSet()

        workspace.name = newName
        workspace.description = input.description?.trim()?.ifEmpty { null }
        /*
         * Null leaves the assignment alone; a list replaces it, empty included —
         * taking every role off a workspace is a decision somebody may make, and it
         * means administrators only.
         */
        input.roleIds?.let { wanted ->
            workspace.roles = wanted.distinct()
                .map { roleId -> roles.findByIdOrNull(roleId) ?: throw RoleNotFoundException(roleId) }
                .toMutableSet()
        }

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
        if (workspace.description != previousDescription) {
            auditRecorder.record(id, WorkspaceAuditCategory.WORKSPACE, "Workspace description updated")
        }
        return workspace
    }

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
