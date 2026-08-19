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
        val tool = tools.findByIdOrNull(id)?.takeIf { access.canSee(it.workspaceId) } ?: return null
        return describe(tool)
    }

    @MutationMapping
    @Transactional
    fun createTool(@Argument input: CreateToolInput): ToolView {
        requireWorkspaceAccess(input.workspaceId)
        val name = input.name.trim()
        if (!IDENTIFIER.matches(name)) throw ToolNameInvalidException(name)
        if (tools.findByWorkspaceIdAndName(input.workspaceId, name) != null) throw ToolNameTakenException(name)

        val code = codeFrom(input.source, input.typescript) ?: starter(name)
        requireParses(code.javascript)

        val tool = tools.save(
            AgentTool(
                workspaceId = input.workspaceId,
                name = name,
                description = input.description?.trim()?.ifEmpty { null },
                source = code.javascript,
                typescript = code.typescript,
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
        val tool = tools.findByIdOrNull(id)?.takeIf { access.canSee(it.workspaceId) } ?: throw ToolNotFoundException(id)

        val previousName = tool.name
        input.name?.trim()?.let { name ->
            if (!IDENTIFIER.matches(name)) throw ToolNameInvalidException(name)
            if (name != tool.name && tools.findByWorkspaceIdAndName(tool.workspaceId, name) != null) {
                throw ToolNameTakenException(name)
            }
            tool.name = name
        }
        input.description?.let { tool.description = it.trim().ifEmpty { null } }
        /*
         * Both halves or neither. A write that moved one would leave the editor
         * showing code the sandbox is not running, which is the one failure this
         * pair exists to prevent.
         */
        codeFrom(input.source, input.typescript)?.let { code ->
            requireParses(code.javascript)
            tool.source = code.javascript
            tool.typescript = code.typescript
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
        val tool = tools.findByIdOrNull(id)?.takeIf { access.canSee(it.workspaceId) } ?: throw ToolNotFoundException(id)

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
        val tool = tools.findByIdOrNull(id)?.takeIf { access.canSee(it.workspaceId) } ?: return false

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
        typescript = tool.typescript,
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
    /**
     * What a new tool starts as.
     *
     * The stub takes no annotations, so the same text is both halves: it is
     * valid TypeScript, and it is what the sandbox would run.
     */
    private fun starter(name: String): ToolCode {
        val stub = """
            export default async function $name(input) {
              // What this returns is handed back to the agent that called it.
              return { ok: true };
            }
        """.trimIndent()
        return ToolCode(javascript = stub, typescript = stub)
    }

    /**
     * The pair, from what arrived — or null when neither half was sent, which
     * means "leave the code alone" on an update and "start from a stub" on a
     * create.
     */
    private fun codeFrom(javascript: String?, typescript: String?): ToolCode? {
        val compiled = javascript?.takeIf { it.isNotBlank() }
        val written = typescript?.takeIf { it.isNotBlank() }
        return when {
            compiled != null && written != null -> ToolCode(javascript = compiled, typescript = written)
            compiled != null -> throw ToolCodeIncompleteException("TypeScript this JavaScript was compiled from")
            written != null -> throw ToolCodeIncompleteException("JavaScript compiled from this TypeScript")
            else -> null
        }
    }

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
    /** Both left out for a new tool, which starts from a stub that parses. */
    val source: String? = null,
    val typescript: String? = null,
)

data class UpdateToolInput(
    val name: String? = null,
    val description: String? = null,
    val source: String? = null,
    val typescript: String? = null,
)

/** What runs, and what it was written as. Saved together, always. */
data class ToolCode(val javascript: String, val typescript: String)

data class ToolView(
    val id: Long,
    val workspaceId: Long,
    val name: String,
    val description: String?,
    val source: String,
    val typescript: String,
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
