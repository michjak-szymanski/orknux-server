package io.mszymanski.orknux.server.agent

import io.mszymanski.orknux.server.action.ValueType
import io.mszymanski.orknux.server.action.typeScriptType
import io.mszymanski.orknux.server.obj.ObjectNotFoundException
import io.mszymanski.orknux.server.obj.WorkflowObjectRepository
import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
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
import io.mszymanski.orknux.server.revision.ComponentRevisionKind
import io.mszymanski.orknux.server.revision.ComponentRevisionRecorder
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
    private val objects: WorkflowObjectRepository,
    private val access: WorkspaceAccess,
    private val auditRecorder: WorkspaceAuditRecorder,
    private val revisions: ComponentRevisionRecorder,
    private val grants: AgentGrants,
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

        /*
         * A tool that says nothing about what it takes takes what every tool used
         * to take: one object called `input`. Left as a default rather than as an
         * empty list, so a caller written before parameters existed - the MCP
         * tools, a duplicate, a test - still creates a tool an agent can call.
         */
        val params = (input.params ?: listOf(DEFAULT_PARAM)).toParams(input.workspaceId)
        val code = codeFrom(input.source, input.typescript) ?: starter(name, params)
        requireParses(code.javascript)

        val tool = tools.save(
            AgentTool(
                workspaceId = input.workspaceId,
                name = name,
                description = input.description?.trim()?.ifEmpty { null },
                source = code.javascript,
                typescript = code.typescript,
                params = params,
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

        // What it is about to stop being. A tool has no draft, so a save is a
        // version; the recorder holds that rule, this door only reports.
        revisions.saved(tool)

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
        /*
         * Null leaves them alone, an empty list takes them all off - the same
         * bargain a function's parameters are saved under, so a client that only
         * meant to rename a tool does not have to resend its signature to keep it.
         */
        input.params?.let { tool.params = it.toParams(tool.workspaceId) }
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

        // The toggle is a save like any other: it changes what the workspace
        // has, and a version of it is what it was a moment ago.
        revisions.saved(tool)
        tool.enabled = enabled
        tool.lastModifiedAt = OffsetDateTime.now()
        tool.lastModifiedBy = currentUser()
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

    /**
     * Removes a tool, unless an agent was granted it.
     *
     * A grant is a name rather than an id, so nothing here would have been left
     * dangling: the agent would simply have stopped being able to do this, with
     * its own screen still listing the grant and nothing anywhere saying what
     * changed. [AgentGrants] is where that argument is written down.
     */
    @MutationMapping
    @Transactional
    fun deleteTool(@Argument id: Long): Boolean {
        val tool = tools.findByIdOrNull(id)?.takeIf { access.canSee(it.workspaceId) } ?: return false

        val granted = grants.toTool(tool.workspaceId, tool.name)
        if (granted.isNotEmpty()) throw ToolInUseException(tool.name, granted)

        tools.delete(tool)
        revisions.forget(ComponentRevisionKind.TOOL, id)
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
        params = tool.params.map { param ->
            ToolParamView(
                name = param.name,
                type = param.type,
                objectId = param.objectId,
                objectName = param.objectId?.let { objects.findByIdOrNull(it)?.name },
            )
        },
        signature = tool.signature,
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
     * What a new tool starts as: a declaration that already takes what the tool
     * says it takes.
     *
     * Printed twice from one list, the way a function's stub is: the TypeScript
     * is what somebody opens and the JavaScript is what runs, and the only
     * difference between them is the annotations. They are not two versions that
     * could drift.
     */
    private fun starter(name: String, params: List<AgentToolParam>): ToolCode {
        fun body(arguments: String) = """
            export default async function $name($arguments) {
              // What this returns is handed back to the agent that called it.
              return { ok: true };
            }
        """.trimIndent()

        val annotated = params.joinToString(", ") {
            "${it.name}: ${typeScriptType(it.type, it.objectId?.let { id -> objects.findByIdOrNull(id)?.name })}"
        }
        return ToolCode(javascript = body(params.joinToString(", ") { it.name }), typescript = body(annotated))
    }

    /**
     * The stored parameters, from what arrived.
     *
     * Names are checked the way a function's are - a parameter the sandbox
     * cannot bind is not a parameter - and so is uniqueness, which a function
     * does not have to check because it passes its arguments positionally and
     * nothing addresses them by name. A tool's are addressed by name, by the
     * model, so two alike is a hole rather than a curiosity.
     */
    private fun List<ToolParamInput>.toParams(workspaceId: Long): MutableList<AgentToolParam> {
        val seen = mutableSetOf<String>()
        return map { param ->
            val name = param.name.trim()
            if (!IDENTIFIER.matches(name)) throw ToolParamInvalidException(name)
            if (!seen.add(name)) throw ToolParamDuplicateException(name)
            AgentToolParam(
                name = name,
                type = param.type,
                // Only an object parameter keeps one. Anything else is cleared
                // rather than carried: a stale id under a string is one that
                // comes back the day the type changes again.
                objectId = if (param.type == ValueType.OBJECT) {
                    requireObject(param.objectId, workspaceId, name)
                } else {
                    null
                },
            )
        }.toMutableList()
    }

    /**
     * The object a parameter names, checked against the workspace claiming it.
     *
     * An id from another workspace is answered as though it does not exist,
     * because from where the caller stands it does not.
     */
    private fun requireObject(objectId: Long?, workspaceId: Long, param: String): Long {
        val id = objectId ?: throw ToolObjectRequiredException(param)
        val found = objects.findByIdOrNull(id) ?: throw ObjectNotFoundException(id)
        if (found.workspaceId != workspaceId) throw ObjectNotFoundException(id)
        return id
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
        access.requireVisible(workspaceId)
    }

    private companion object {
        /** A name JavaScript can call: what the source is written against. */
        val IDENTIFIER = Regex("[A-Za-z_$][A-Za-z0-9_$]{0,63}")

        /**
         * What a tool takes when nobody said: one object, called `input`.
         *
         * The signature every tool had before they had signatures. Kept as the
         * default so the description an agent reads - "it takes whatever it
         * needs in `input`" - goes on being true for a tool created without one.
         */
        val DEFAULT_PARAM = ToolParamInput(name = "input", type = ValueType.MAP)
    }
}

/** One argument a tool takes, as the editor sends it. */
data class ToolParamInput(
    val name: String,
    val type: ValueType,
    /** Required when the type is OBJECT, and ignored otherwise. */
    val objectId: Long? = null,
)

data class CreateToolInput(
    val workspaceId: Long,
    val name: String,
    val description: String? = null,
    /** Both left out for a new tool, which starts from a stub that parses. */
    val source: String? = null,
    val typescript: String? = null,
    /** Left out means the one every tool used to take: an object called `input`. */
    val params: List<ToolParamInput>? = null,
)

data class UpdateToolInput(
    val name: String? = null,
    val description: String? = null,
    val source: String? = null,
    val typescript: String? = null,
    /** Null leaves them alone; an empty list takes them all off. */
    val params: List<ToolParamInput>? = null,
)

data class ToolParamView(
    val name: String,
    val type: ValueType,
    /** Which object, when the type is OBJECT. Null otherwise. */
    val objectId: Long?,
    /** What that object is called, resolved here for the editor's annotations. */
    val objectName: String?,
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
    val params: List<ToolParamView>,
    /** "(city: string, days: number)", ready for the list. */
    val signature: String,
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
