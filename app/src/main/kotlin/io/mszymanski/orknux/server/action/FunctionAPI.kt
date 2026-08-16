package io.mszymanski.orknux.server.action

import io.mszymanski.orknux.server.condition.WorkflowConditionRepository
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
 * A workspace's JavaScript functions: what an action calls when it transforms
 * something.
 *
 * The source is never run here except to be parsed — `validateFunction` backs
 * the editor's Validate — and even that happens in the sandbox, so a script
 * cannot reach anything by being compiled.
 */
@Controller
class FunctionAPI(
    private val functions: WorkflowFunctionRepository,
    private val actions: WorkflowActionRepository,
    private val conditions: WorkflowConditionRepository,
    private val scripts: ScriptRunner,
    private val workspaces: WorkspaceRepository,
    private val access: WorkspaceAccess,
    private val auditRecorder: WorkspaceAuditRecorder,
) {

    @QueryMapping
    fun workspaceFunctions(@Argument workspaceId: Long, @Argument page: Int?, @Argument size: Int?): FunctionPage {
        requireWorkspaceAccess(workspaceId)
        return FunctionPage(functions.findByWorkspaceId(workspaceId, pageRequest(page, size, Sort.by("name"))), ::describe)
    }

    @QueryMapping
    fun function(@Argument id: Long): FunctionView? {
        val function = functions.findByIdOrNull(id) ?: return null
        requireWorkspaceAccess(function.workspaceId)
        return describe(function)
    }

    @MutationMapping
    @Transactional
    fun createFunction(@Argument input: CreateFunctionInput): FunctionView {
        requireWorkspaceAccess(input.workspaceId)
        val name = input.name.trim()
        requireIdentifier(name) { FunctionNameInvalidException(name) }
        if (functions.findByWorkspaceIdAndName(input.workspaceId, name) != null) throw FunctionNameTakenException(name)

        val source = input.source?.takeIf { it.isNotBlank() } ?: starter(name, input.params.orEmpty())
        requireParses(source)

        val function = functions.save(
            WorkflowFunction(
                workspaceId = input.workspaceId,
                name = name,
                description = input.description?.trim()?.ifEmpty { null },
                source = source,
                returnType = input.returnType ?: ValueType.OBJECT,
                params = input.params.orEmpty().toParams(),
                lastModifiedAt = OffsetDateTime.now(),
                lastModifiedBy = currentUser(),
            ),
        )

        auditRecorder.record(input.workspaceId, WorkspaceAuditCategory.WORKFLOW, "Function $name created")
        return describe(function)
    }

    /** Backs the editor: the code, the details panel and the parameter list. */
    @MutationMapping
    @Transactional
    fun updateFunction(@Argument id: Long, @Argument input: UpdateFunctionInput): FunctionView {
        val function = functions.findByIdOrNull(id) ?: throw FunctionNotFoundException(id)
        requireWorkspaceAccess(function.workspaceId)

        val previousName = function.name
        input.name?.trim()?.let { name ->
            requireIdentifier(name) { FunctionNameInvalidException(name) }
            if (name != function.name && functions.findByWorkspaceIdAndName(function.workspaceId, name) != null) {
                throw FunctionNameTakenException(name)
            }
            function.name = name
        }
        input.description?.let { function.description = it.trim().ifEmpty { null } }
        input.source?.let {
            requireParses(it)
            function.source = it
        }
        input.returnType?.let { function.returnType = it }
        input.params?.let { function.params = it.toParams() }
        function.lastModifiedAt = OffsetDateTime.now()
        function.lastModifiedBy = currentUser()

        val message = if (previousName == function.name) {
            "Function ${function.name} updated"
        } else {
            "Function $previousName renamed to ${function.name}"
        }
        auditRecorder.record(function.workspaceId, WorkspaceAuditCategory.WORKFLOW, message)
        return describe(function)
    }

    /**
     * The editor's Validate: parses the source and says where it broke.
     *
     * It answers rather than throws, because a syntax error is what the button
     * is for, not a failed request.
     */
    @MutationMapping
    fun validateFunctionSource(@Argument workspaceId: Long, @Argument source: String): FunctionValidationView {
        requireWorkspaceAccess(workspaceId)
        val checked = scripts.validate(source)
        return FunctionValidationView(checked.valid, checked.message, checked.line, checked.column)
    }

    @MutationMapping
    @Transactional
    fun deleteFunction(@Argument id: Long): Boolean {
        val function = functions.findByIdOrNull(id) ?: return false
        requireWorkspaceAccess(function.workspaceId)

        // Anything pointing at a deleted function would have nothing to call,
        // and the caller can see what to change first.
        val callers = actions.findByFunctionId(id).map { it.name } +
            conditions.findByWorkspaceId(function.workspaceId).filter { it.functionId == id }.map { it.name }
        if (callers.isNotEmpty()) throw FunctionInUseException(function.name, callers)

        functions.delete(function)
        auditRecorder.record(function.workspaceId, WorkspaceAuditCategory.WORKFLOW, "Function ${function.name} deleted")
        return true
    }

    private fun describe(function: WorkflowFunction) = FunctionView(
        id = requireNotNull(function.id),
        workspaceId = function.workspaceId,
        name = function.name,
        description = function.description,
        source = function.source,
        returnType = function.returnType,
        params = function.params.map { FunctionParamView(it.name, it.type) },
        signature = function.signature,
        lastModifiedAt = function.lastModifiedAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        lastModifiedBy = function.lastModifiedBy,
    )

    private fun requireParses(source: String) {
        val checked = scripts.validate(source)
        if (!checked.valid) throw FunctionSourceInvalidException(checked.message ?: "The script could not be parsed")
    }

    /**
     * What a new function starts as: something that runs, and that returns what
     * it was given rather than an empty object, so the first run of a workflow
     * shows the shape reaching it.
     */
    private fun starter(name: String, params: List<FunctionParamInput>): String {
        val arguments = params.joinToString(", ") { it.name }
        val returned = when {
            params.isEmpty() -> "{}"
            else -> params.joinToString(", ", "{ ", " }") { it.name }
        }
        return """
            export default async function $name($arguments) {
              // What this returns is handed to the next node.
              return $returned;
            }
        """.trimIndent()
    }

    private fun List<FunctionParamInput>.toParams(): MutableList<FunctionParam> = map { param ->
        val name = param.name.trim()
        requireIdentifier(name) { FunctionParamInvalidException(name) }
        FunctionParam(name = name, type = param.type)
    }.toMutableList()

    private fun requireIdentifier(name: String, failure: () -> RuntimeException) {
        if (!IDENTIFIER.matches(name)) throw failure()
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

data class FunctionParamInput(val name: String, val type: ValueType)

data class CreateFunctionInput(
    val workspaceId: Long,
    val name: String,
    val description: String? = null,
    /** Left out for a new function, which starts from a stub that runs. */
    val source: String? = null,
    val returnType: ValueType? = null,
    val params: List<FunctionParamInput>? = null,
)

data class UpdateFunctionInput(
    val name: String? = null,
    val description: String? = null,
    val source: String? = null,
    val returnType: ValueType? = null,
    val params: List<FunctionParamInput>? = null,
)

data class FunctionParamView(val name: String, val type: ValueType)

data class FunctionView(
    val id: Long,
    val workspaceId: Long,
    val name: String,
    val description: String?,
    val source: String,
    val returnType: ValueType,
    val params: List<FunctionParamView>,
    /** "(input: object, format: string)", ready for the list. */
    val signature: String,
    val lastModifiedAt: String,
    val lastModifiedBy: String,
)

data class FunctionValidationView(
    val valid: Boolean,
    val message: String?,
    val line: Int?,
    val column: Int?,
)

data class FunctionPage(
    val content: List<FunctionView>,
    val page: Int,
    val size: Int,
    val totalElements: Int,
    val totalPages: Int,
) {
    constructor(page: Page<WorkflowFunction>, describe: (WorkflowFunction) -> FunctionView) : this(
        content = page.content.map(describe),
        page = page.number,
        size = page.size,
        totalElements = page.totalElements.toInt(),
        totalPages = page.totalPages,
    )
}
