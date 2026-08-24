package io.mszymanski.orknux.server.variable

import io.mszymanski.orknux.server.dependency.ComponentDependants
import io.mszymanski.orknux.server.dependency.phrases
import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import io.mszymanski.orknux.server.workspace.pageRequest
import org.springframework.data.domain.Page
import org.springframework.data.domain.Sort
import org.springframework.data.repository.findByIdOrNull
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional
import java.time.format.DateTimeFormatter

/**
 * A workspace's variables: named values its functions are handed.
 *
 * The value goes in and never comes back. Nothing here returns it, and no screen
 * asks for it — what a variable is *for* is being read by a function inside the
 * sandbox, which is the one place it is needed. Changing a variable means
 * writing a new value over the old one rather than editing what is there.
 */
@Controller
class VariableAPI(
    private val variables: WorkspaceVariableRepository,
    private val catalogs: VariableCatalogRepository,
    /**
     * Asked what still reads a variable before one is removed or made readable.
     *
     * Five sources behind one call: a function taking it as an external
     * parameter, an action whose headers read it, and the model provider,
     * connection and MCP server that authenticate with it — because a credential
     * is not one card's any more. Since #244 every secret field in the product
     * may read a workspace secret, and a guard that only knows about model
     * providers is a guard that lets somebody delete the variable a Slack
     * connection posts with.
     *
     * Asked here rather than assembled here, so that the sentence this refuses
     * with and the list the variable's own screen draws are the same rows.
     */
    private val dependants: ComponentDependants,
    private val workspaces: WorkspaceRepository,
    private val access: WorkspaceAccess,
    private val auditRecorder: WorkspaceAuditRecorder,
) {

    /** The workspace's catalogs, by name, each with what it holds. */
    @QueryMapping
    fun variableCatalogs(@Argument workspaceId: Long): List<VariableCatalogView> {
        requireWorkspaceAccess(workspaceId)
        return catalogs.findByWorkspaceIdOrderByNameAsc(workspaceId).map(::describe)
    }

    /**
     * One page of variables: a catalog's, or the whole workspace's.
     *
     * @param catalogId which catalog to look in; omitted, every variable the
     *   workspace holds, which is what a search across catalogs wants.
     * @param search a name, or part of one; blank is no filter rather than a
     *   search for nothing.
     */
    @QueryMapping
    fun workspaceVariables(
        @Argument workspaceId: Long,
        @Argument catalogId: Long?,
        @Argument page: Int?,
        @Argument size: Int?,
        @Argument search: String?,
    ): VariablePage {
        requireWorkspaceAccess(workspaceId)
        val looking = search?.trim().orEmpty()
        val pageable = pageRequest(page, size, Sort.by("name"))

        val catalog = catalogId?.let { requireCatalog(it, workspaceId) }
        val held = when {
            catalog == null && looking.isEmpty() -> variables.findByWorkspaceIdOrderByNameAsc(workspaceId, pageable)
            catalog == null -> variables.findByWorkspaceIdAndNameContainingIgnoreCaseOrderByNameAsc(
                workspaceId,
                looking,
                pageable,
            )

            looking.isEmpty() -> variables.findByCatalogIdOrderByNameAsc(requireNotNull(catalog.id), pageable)
            else -> variables.findByCatalogIdAndNameContainingIgnoreCaseOrderByNameAsc(
                requireNotNull(catalog.id),
                looking,
                pageable,
            )
        }
        return VariablePage(held, ::describe)
    }

    @MutationMapping
    @Transactional
    fun createVariableCatalog(@Argument workspaceId: Long, @Argument name: String): VariableCatalogView {
        requireWorkspaceAccess(workspaceId)
        val trimmed = name.trim()
        if (trimmed.isEmpty()) throw VariableCatalogNameInvalidException()
        if (catalogs.findByWorkspaceIdAndName(workspaceId, trimmed) != null) {
            throw VariableCatalogNameTakenException(trimmed)
        }

        val saved = catalogs.save(
            VariableCatalog(workspaceId = workspaceId, name = trimmed, createdBy = currentUser()),
        )
        auditRecorder.record(workspaceId, WorkspaceAuditCategory.WORKFLOW, "Catalog $trimmed created")
        return describe(saved)
    }

    @MutationMapping
    @Transactional
    fun renameVariableCatalog(@Argument id: Long, @Argument name: String): VariableCatalogView {
        val catalog = catalogs.findByIdOrNull(id)?.takeIf { access.canSee(it.workspaceId) }
            ?: throw VariableCatalogNotFoundException(id)

        val trimmed = name.trim()
        if (trimmed.isEmpty()) throw VariableCatalogNameInvalidException()
        if (trimmed != catalog.name && catalogs.findByWorkspaceIdAndName(catalog.workspaceId, trimmed) != null) {
            throw VariableCatalogNameTakenException(trimmed)
        }

        val previous = catalog.name
        catalog.name = trimmed
        auditRecorder.record(
            catalog.workspaceId,
            WorkspaceAuditCategory.WORKFLOW,
            "Catalog $previous renamed to $trimmed",
        )
        return describe(catalog)
    }

    /**
     * Removes an empty catalog.
     *
     * Never its contents with it: a catalog is a folder, and what is in it is
     * somebody's secret that something may be built on. Emptying it is a
     * decision to make one variable at a time.
     */
    @MutationMapping
    @Transactional
    fun deleteVariableCatalog(@Argument id: Long): Boolean {
        val catalog = catalogs.findByIdOrNull(id)?.takeIf { access.canSee(it.workspaceId) } ?: return false

        val held = variables.countByCatalogId(id)
        if (held > 0) throw VariableCatalogNotEmptyException(catalog.name, held)

        catalogs.delete(catalog)
        auditRecorder.record(catalog.workspaceId, WorkspaceAuditCategory.WORKFLOW, "Catalog ${catalog.name} removed")
        return true
    }

    @QueryMapping
    fun variable(@Argument id: Long): VariableView? {
        val variable = variables.findByIdOrNull(id)?.takeIf { access.canSee(it.workspaceId) } ?: return null
        return describe(variable)
    }

    @MutationMapping
    @Transactional
    fun createVariable(@Argument input: CreateVariableInput): VariableView {
        requireWorkspaceAccess(input.workspaceId)
        val catalog = requireCatalog(input.catalogId, input.workspaceId)
        val name = requireNameable(input.name)
        if (variables.findByCatalogIdAndName(input.catalogId, name) != null) {
            throw VariableNameTakenException(name, catalog.name)
        }

        val saved = variables.save(
            WorkspaceVariable(
                workspaceId = input.workspaceId,
                catalogId = input.catalogId,
                name = name,
                description = input.description?.trim()?.ifEmpty { null },
                type = input.type,
                kind = input.kind,
                value = input.value?.takeIf { it.isNotEmpty() },
                createdBy = currentUser(),
                lastModifiedBy = currentUser(),
            ),
        )
        auditRecorder.record(input.workspaceId, WorkspaceAuditCategory.WORKFLOW, "Variable $name created")
        return describe(saved)
    }

    /**
     * Backs the variable form: the name, the type, and a new value.
     *
     * A null value leaves what is stored alone, because the form cannot show it
     * and so cannot send it back — asking somebody to retype a secret to rename
     * a variable would be a good way to have them stop using variables.
     */
    @MutationMapping
    @Transactional
    fun updateVariable(@Argument id: Long, @Argument input: UpdateVariableInput): VariableView {
        val variable = variables.findByIdOrNull(id)?.takeIf { access.canSee(it.workspaceId) }
            ?: throw VariableNotFoundException(id)

        // Moved first, so a name is checked against the catalog it is going to
        // rather than the one it is leaving.
        input.catalogId?.let { variable.catalogId = requireCatalog(it, variable.workspaceId).id ?: variable.catalogId }

        val previousName = variable.name
        input.name?.let { said ->
            val name = requireNameable(said)
            val clash = variables.findByCatalogIdAndName(variable.catalogId, name)
            if (name != variable.name && clash != null && clash.id != variable.id) {
                throw VariableNameTakenException(name, catalogName(variable.catalogId))
            }
            variable.name = name
        }
        input.description?.let { variable.description = it.trim().ifEmpty { null } }
        input.type?.let { variable.type = it }
        input.kind?.let { kind ->
            // A VALUE is returned with the listing, so a credential turned into
            // one is a credential on a screen. The other end of the rule that
            // refuses to bind a provider to anything but a SECRET.
            if (kind != VariableKind.SECRET && variable.kind == VariableKind.SECRET) {
                val credentialOf = credentialOf(variable)
                if (credentialOf.isNotEmpty()) throw VariableSecrecyHeldException(variable.name, credentialOf)
            }
            variable.kind = kind
        }
        input.value?.takeIf { it.isNotEmpty() }?.let { variable.value = it }
        variable.lastModifiedAt = java.time.OffsetDateTime.now()
        variable.lastModifiedBy = currentUser()

        val message = if (previousName == variable.name) {
            "Variable ${variable.name} updated"
        } else {
            "Variable $previousName renamed to ${variable.name}"
        }
        auditRecorder.record(variable.workspaceId, WorkspaceAuditCategory.WORKFLOW, message)
        return describe(variable)
    }

    /**
     * Shows what a secret holds, once, to somebody who asked.
     *
     * Nothing else returns it — not the list, not the form — because a value on
     * screen is a value in a screenshot, a recording, or the shoulder of the
     * person walking past. Asking for it is a deliberate act, so it is recorded
     * as one: the audit log says who looked and when, which is the whole of what
     * makes revealing it safe to offer.
     */
    @MutationMapping
    @Transactional
    fun revealVariable(@Argument id: Long): String? {
        val variable = variables.findByIdOrNull(id)?.takeIf { access.canSee(it.workspaceId) }
            ?: throw VariableNotFoundException(id)

        auditRecorder.record(
            variable.workspaceId,
            WorkspaceAuditCategory.WORKFLOW,
            "Variable ${variable.name} revealed",
        )
        return variable.value
    }

    /**
     * Removes it, unless a function is built on it.
     *
     * An external parameter is part of a function's signature, so taking the
     * variable away would silently change what that function is handed. Said out
     * loud, with the functions named, rather than cascading.
     */
    @MutationMapping
    @Transactional
    fun deleteVariable(@Argument id: Long): Boolean {
        val variable = variables.findByIdOrNull(id)?.takeIf { access.canSee(it.workspaceId) } ?: return false

        /*
         * An action's header reads a variable the same way a function's external
         * parameter does, so it holds it in place the same way. Read out of the
         * JSON rather than joined to, because that is where the reference is
         * kept - there is no column for a foreign key to guard, so the guard is
         * here or it is nowhere, and nowhere means a header that names a variable
         * nobody can find and a request that fails at three in the morning.
         */
        val usedBy = dependants.signatureOfVariable(id)
        if (usedBy.isNotEmpty()) throw VariableInUseException(variable.name, usedBy.phrases())

        /*
         * And anything reading it for a credential holds it in place too - see
         * [VariableHeldAsCredentialException] for why that is a refusal rather
         * than a warning. Asked of the connection module, which owns all three
         * kinds of holder, rather than joined to: there is no foreign key across
         * that boundary and there is not meant to be one.
         */
        val credentialOf = credentialOf(variable)
        if (credentialOf.isNotEmpty()) throw VariableHeldAsCredentialException(variable.name, credentialOf)

        variables.delete(variable)
        auditRecorder.record(
            variable.workspaceId,
            WorkspaceAuditCategory.WORKFLOW,
            "Variable ${variable.name} removed",
        )
        return true
    }

    /**
     * Everything reading this variable for a credential, each named the way a
     * sentence would name it.
     *
     * One list rather than three, and the noun is carried by the entry rather
     * than by the sentence around it: "the connection Slack, the MCP server
     * brave-search" reads, and a bare "Slack, brave-search" leaves whoever hit
     * the refusal to go and find out what those are. The order is by kind and
     * then by name, so two runs say the same thing.
     *
     * The sentence is assembled here and the rows come from
     * [ComponentDependants], which is the same set the variable's own screen
     * lists as links.
     */
    private fun credentialOf(variable: WorkspaceVariable): List<String> =
        dependants.credentialOfVariable(requireNotNull(variable.id)).phrases()

    /**
     * A name a function can receive as an argument.
     *
     * The value arrives inside the sandbox as a parameter of that name, so
     * anything that is not an identifier would be a variable that can be made
     * and never used.
     */
    private fun requireNameable(said: String): String {
        val name = said.trim()
        if (!NAME.matches(name)) throw VariableNameInvalidException(name)
        return name
    }

    private fun describe(catalog: VariableCatalog) = VariableCatalogView(
        id = requireNotNull(catalog.id),
        workspaceId = catalog.workspaceId,
        name = catalog.name,
        variableCount = variables.countByCatalogId(requireNotNull(catalog.id)).toInt(),
        createdAt = catalog.createdAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        createdBy = catalog.createdBy,
    )

    private fun describe(variable: WorkspaceVariable) = VariableView(
        id = requireNotNull(variable.id),
        workspaceId = variable.workspaceId,
        catalogId = variable.catalogId,
        catalogName = catalogName(variable.catalogId),
        name = variable.name,
        description = variable.description,
        type = variable.type,
        kind = variable.kind,
        // A value is read with the list; a secret is not, and asking for one is
        // what `revealVariable` is — recorded, deliberate, one at a time.
        value = variable.value.takeIf { variable.kind == VariableKind.VALUE },
        valueSet = !variable.value.isNullOrEmpty(),
        createdAt = variable.createdAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        createdBy = variable.createdBy,
        lastModifiedAt = variable.lastModifiedAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        lastModifiedBy = variable.lastModifiedBy,
    )

    /** The catalog, and that it is this workspace's rather than another's. */
    private fun requireCatalog(id: Long, workspaceId: Long): VariableCatalog {
        val catalog = catalogs.findByIdOrNull(id) ?: throw VariableCatalogNotFoundException(id)
        if (catalog.workspaceId != workspaceId) throw VariableCatalogNotFoundException(id)
        return catalog
    }

    /** For a view; a catalog that has gone is not a reason to fail reading a list. */
    private fun catalogName(id: Long): String = catalogs.findByIdOrNull(id)?.name ?: "—"

    private fun requireWorkspaceAccess(workspaceId: Long) {
        access.requireVisible(workspaceId)
    }

    private fun currentUser(): String =
        SecurityContextHolder.getContext().authentication?.name ?: "system"

    private companion object {
        val NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
    }
}

data class CreateVariableInput(
    val workspaceId: Long,
    /** Which catalog holds it; every variable is in one. */
    val catalogId: Long,
    val name: String,
    /** What it is for, since the name has to be an identifier. */
    val description: String? = null,
    val type: VariableType,
    /** Whether it may be read with the list, or only on request. */
    val kind: VariableKind = VariableKind.SECRET,
    /** What it holds; a variable may be made before its value is known. */
    val value: String? = null,
)

data class UpdateVariableInput(
    /** Moves it to another catalog; null leaves it where it is. */
    val catalogId: Long? = null,
    val name: String? = null,
    val description: String? = null,
    val type: VariableType? = null,
    /** Whether it may be read with the list, or only on request. */
    val kind: VariableKind? = null,
    /** Null leaves the stored value alone; a secret's form cannot show it to send it back. */
    val value: String? = null,
)

/** A folder of variables, as the list beside them shows it. */
data class VariableCatalogView(
    val id: Long,
    val workspaceId: Long,
    val name: String,
    /** What the count badge shows, so an empty catalog reads as empty. */
    val variableCount: Int,
    val createdAt: String,
    val createdBy: String,
)

data class VariableView(
    val id: Long,
    val workspaceId: Long,
    val catalogId: Long,
    val catalogName: String,
    val name: String,
    val description: String?,
    val type: VariableType,
    val kind: VariableKind,
    /** What it holds, on a value. Null on a secret, whatever is stored. */
    val value: String?,
    /** Whether anything is stored, which is all a secret says about itself. */
    val valueSet: Boolean,
    /** ISO-8601 offset date-time. */
    val createdAt: String,
    /** Who put it there; the person who knows what it is for. */
    val createdBy: String,
    /** ISO-8601 offset date-time. */
    val lastModifiedAt: String,
    val lastModifiedBy: String,
)

data class VariablePage(
    val content: List<VariableView>,
    val page: Int,
    val size: Int,
    val totalElements: Int,
    val totalPages: Int,
) {
    constructor(page: Page<WorkspaceVariable>, describe: (WorkspaceVariable) -> VariableView) : this(
        content = page.content.map(describe),
        page = page.number,
        size = page.size,
        totalElements = page.totalElements.toInt(),
        totalPages = page.totalPages,
    )
}
