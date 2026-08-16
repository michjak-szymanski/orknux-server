package io.mszymanski.orknux.server.agent

import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import io.mszymanski.orknux.server.workspace.WorkspaceNotFoundException
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import io.mszymanski.orknux.server.workspace.pageRequest
import io.mszymanski.orknux.workflow.script.ScriptRunner
import org.springframework.data.domain.Page
import org.springframework.data.domain.Sort
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * A workspace's tools: the JavaScript its agents may call while they run.
 *
 * The source is never run here except to be parsed — `validateToolSource` backs
 * the editor's Validate — and even that happens in the sandbox, so a script
 * cannot reach anything by being compiled.
 */
@Controller
class ToolAPI(
    private val tools: AgentToolRepository,
    private val scripts: ScriptRunner,
    private val workspaces: WorkspaceRepository,
    private val access: WorkspaceAccess,
    private val auditRecorder: WorkspaceAuditRecorder,
) {

    @QueryMapping
    fun workspaceTools(@Argument workspaceId: Long, @Argument page: Int?, @Argument size: Int?): ToolPage {
        requireWorkspaceAccess(workspaceId)
        return ToolPage(tools.findByWorkspaceId(workspaceId, pageRequest(page, size, Sort.by("name"))), ::describe)
    }

    @QueryMapping
    fun tool(@Argument id: Long): ToolView? {
        val tool = tools.findByIdOrNull(id) ?: return null
        requireWorkspaceAccess(tool.workspaceId)
        return describe(tool)
    }

    @MutationMapping
    @Transactional
    fun createTool(@Argument input: CreateToolInput): ToolView {
        requireWorkspaceAccess(input.workspaceId)
        val name = input.name.trim()
        if (!IDENTIFIER.matches(name)) throw ToolNameInvalidException(name)
        if (tools.findByWorkspaceIdAndName(input.workspaceId, name) != null) throw ToolNameTakenException(name)

        val source = input.source?.takeIf { it.isNotBlank() } ?: starter(name)
        requireParses(source)

        val tool = tools.save(
            AgentTool(
                workspaceId = input.workspaceId,
                name = name,
                description = input.description?.trim()?.ifEmpty { null },
                source = source,
                lastModifiedAt = OffsetDateTime.now(),
                lastModifiedBy = currentUser(),
            ),
        )
        auditRecorder.record(input.workspaceId, WorkspaceAuditCategory.AGENT, "Tool $name created")
        return describe(tool)
    }

    /** Backs the editor: the code on the left, the details on the right. */
    @MutationMapping
    @Transactional
    fun updateTool(@Argument id: Long, @Argument input: UpdateToolInput): ToolView {
        val tool = tools.findByIdOrNull(id) ?: throw ToolNotFoundException(id)
        requireWorkspaceAccess(tool.workspaceId)

        val previousName = tool.name
        input.name?.trim()?.let { name ->
            if (!IDENTIFIER.matches(name)) throw ToolNameInvalidException(name)
            if (name != tool.name && tools.findByWorkspaceIdAndName(tool.workspaceId, name) != null) {
                throw ToolNameTakenException(name)
            }
            tool.name = name
        }
        input.description?.let { tool.description = it.trim().ifEmpty { null } }
        input.source?.let {
            requireParses(it)
            tool.source = it
        }
        tool.lastModifiedAt = OffsetDateTime.now()
        tool.lastModifiedBy = currentUser()

        val message = if (previousName == tool.name) {
            "Tool ${tool.name} updated"
        } else {
            "Tool $previousName renamed to ${tool.name}"
        }
        auditRecorder.record(tool.workspaceId, WorkspaceAuditCategory.AGENT, message)
        return describe(tool)
    }

    /** The toggle on the list: out of reach, but still there. */
    @MutationMapping
    @Transactional
    fun setToolEnabled(@Argument id: Long, @Argument enabled: Boolean): ToolView {
        val tool = tools.findByIdOrNull(id) ?: throw ToolNotFoundException(id)
        requireWorkspaceAccess(tool.workspaceId)

        tool.enabled = enabled
        val what = if (enabled) "enabled" else "disabled"
        auditRecorder.record(tool.workspaceId, WorkspaceAuditCategory.AGENT, "Tool ${tool.name} $what")
        return describe(tool)
    }

    /**
     * The editor's Validate: parses the source and says where it broke.
     *
     * It answers rather than throws, because a syntax error is what the button
     * is for, not a failed request.
     */
    @MutationMapping
    fun validateToolSource(@Argument workspaceId: Long, @Argument source: String): SourceValidationView {
        requireWorkspaceAccess(workspaceId)
        val checked = scripts.validate(source)
        return SourceValidationView(checked.valid, checked.message, checked.line, checked.column)
    }

    @MutationMapping
    @Transactional
    fun deleteTool(@Argument id: Long): Boolean {
        val tool = tools.findByIdOrNull(id) ?: return false
        requireWorkspaceAccess(tool.workspaceId)

        tools.delete(tool)
        auditRecorder.record(tool.workspaceId, WorkspaceAuditCategory.AGENT, "Tool ${tool.name} deleted")
        return true
    }

    private fun describe(tool: AgentTool) = ToolView(
        id = requireNotNull(tool.id),
        workspaceId = tool.workspaceId,
        name = tool.name,
        description = tool.description,
        source = tool.source,
        enabled = tool.enabled,
        lastModifiedAt = tool.lastModifiedAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        lastModifiedBy = tool.lastModifiedBy,
    )

    private fun requireParses(source: String) {
        val checked = scripts.validate(source)
        if (!checked.valid) throw ToolSourceInvalidException(checked.message ?: "The script could not be parsed")
    }

    /**
     * What a new tool starts as: something that parses, and that says in a
     * comment what the agent will be reading when it decides to call it.
     */
    private fun starter(name: String): String = """
        export default async function $name(input) {
          // What this returns is handed back to the agent that called it.
          return { ok: true };
        }
    """.trimIndent()

    private fun currentUser(): String =
        SecurityContextHolder.getContext().authentication?.name ?: "system"

    private fun requireWorkspaceAccess(workspaceId: Long) {
        val workspace = workspaces.findByIdOrNull(workspaceId) ?: throw WorkspaceNotFoundException(workspaceId)
        access.requireVisible(workspace)
    }

    private companion object {
        /** A name JavaScript can call: what the source is written against. */
        val IDENTIFIER = Regex("[A-Za-z_$][A-Za-z0-9_$]{0,63}")
    }
}

data class CreateToolInput(
    val workspaceId: Long,
    val name: String,
    val description: String? = null,
    /** Left out for a new tool, which starts from a stub that parses. */
    val source: String? = null,
)

data class UpdateToolInput(
    val name: String? = null,
    val description: String? = null,
    val source: String? = null,
)

data class ToolView(
    val id: Long,
    val workspaceId: Long,
    val name: String,
    val description: String?,
    val source: String,
    val enabled: Boolean,
    val lastModifiedAt: String,
    val lastModifiedBy: String,
)

/** Shared by the tool and skill editors: both have a Validate button. */
data class SourceValidationView(
    val valid: Boolean,
    val message: String?,
    val line: Int?,
    val column: Int?,
)

data class ToolPage(
    val content: List<ToolView>,
    val page: Int,
    val size: Int,
    val totalElements: Int,
    val totalPages: Int,
) {
    constructor(page: Page<AgentTool>, describe: (AgentTool) -> ToolView) : this(
        content = page.content.map(describe),
        page = page.number,
        size = page.size,
        totalElements = page.totalElements.toInt(),
        totalPages = page.totalPages,
    )
}
