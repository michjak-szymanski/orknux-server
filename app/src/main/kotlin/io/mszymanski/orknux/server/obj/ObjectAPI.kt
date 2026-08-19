package io.mszymanski.orknux.server.obj

import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import io.mszymanski.orknux.server.workspace.WorkspaceNotFoundException
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import io.mszymanski.orknux.server.workspace.pageRequest
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
 * A workspace's objects: the shapes its workflows pass around.
 *
 * The catalogue rule applies as it does to triggers, actions and conditions —
 * defined once here, pointed at from everywhere else. What differs is that an
 * object describes data rather than doing anything, so nothing instances one;
 * things simply say they are shaped like it.
 */
@Controller
class ObjectAPI(
    private val objects: WorkflowObjectRepository,
    private val workspaces: WorkspaceRepository,
    private val access: WorkspaceAccess,
    private val auditRecorder: WorkspaceAuditRecorder,
) {

    @QueryMapping
    fun workspaceObjects(@Argument workspaceId: Long, @Argument page: Int?, @Argument size: Int?): ObjectPage {
        requireWorkspaceAccess(workspaceId)
        return ObjectPage(objects.findByWorkspaceId(workspaceId, pageRequest(page, size, Sort.by("name"))), ::describe)
    }

    @QueryMapping
    fun workflowObject(@Argument id: Long): ObjectView? {
        val held = objects.findByIdOrNull(id)?.takeIf { access.canSee(it.workspaceId) } ?: return null
        return describe(held)
    }

    @MutationMapping
    @Transactional
    fun createObject(@Argument input: CreateObjectInput): ObjectView {
        requireWorkspaceAccess(input.workspaceId)
        val name = input.name.trim()
        requireUsableName(name)
        if (objects.findByWorkspaceIdAndName(input.workspaceId, name) != null) throw ObjectNameTakenException(name)

        val saved = objects.save(
            WorkflowObject(
                workspaceId = input.workspaceId,
                name = name,
                description = input.description?.trim()?.ifEmpty { null },
                properties = propertiesOf(input.workspaceId, input.properties.orEmpty()),
                createdBy = currentUser(),
                lastModifiedBy = currentUser(),
            ),
        )

        auditRecorder.record(input.workspaceId, WorkspaceAuditCategory.OBJECT, "Object $name created")
        return describe(saved)
    }

    /** Backs the editor: the properties on the left, the details on the right. */
    @MutationMapping
    @Transactional
    fun updateObject(@Argument id: Long, @Argument input: UpdateObjectInput): ObjectView {
        val held = objects.findByIdOrNull(id) ?: throw ObjectNotFoundException(id)
        requireWorkspaceAccess(held.workspaceId)

        val previousName = held.name
        input.name?.trim()?.let { name ->
            requireUsableName(name)
            if (name != held.name && objects.findByWorkspaceIdAndName(held.workspaceId, name) != null) {
                throw ObjectNameTakenException(name)
            }
            held.name = name
        }
        input.description?.let { held.description = it.trim().ifEmpty { null } }
        input.properties?.let { held.properties = propertiesOf(held.workspaceId, it, self = id) }
        held.lastModifiedAt = OffsetDateTime.now()
        held.lastModifiedBy = currentUser()

        val message = if (previousName == held.name) {
            "Object ${held.name} updated"
        } else {
            "Object $previousName renamed to ${held.name}"
        }
        auditRecorder.record(held.workspaceId, WorkspaceAuditCategory.OBJECT, message)
        return describe(held)
    }

    /**
     * An object still used by another is not one to delete.
     *
     * The same rule a condition follows: what points at it would be left
     * describing a shape that no longer exists, and a property with a dangling
     * reference cannot be shown, checked or offered.
     */
    @MutationMapping
    @Transactional
    fun deleteObject(@Argument id: Long): Boolean {
        val held = objects.findByIdOrNull(id) ?: return false
        requireWorkspaceAccess(held.workspaceId)

        val users = objects.findByWorkspaceId(held.workspaceId)
            .filter { it.id != id && it.properties.any { property -> property.refObjectId == id } }
            .map { it.name }
        if (users.isNotEmpty()) throw ObjectInUseException(held.name, users)

        objects.delete(held)
        auditRecorder.record(held.workspaceId, WorkspaceAuditCategory.OBJECT, "Object ${held.name} deleted")
        return true
    }

    /**
     * The editor's Validate. It answers rather than throws: a half-written
     * property is what the button is for, not a failed request.
     */
    @MutationMapping
    fun validateObject(@Argument workspaceId: Long, @Argument properties: List<ObjectPropertyInput>): ObjectValidationView {
        requireWorkspaceAccess(workspaceId)

        val seen = mutableSetOf<String>()
        properties.forEachIndexed { index, property ->
            val name = property.name.trim()
            val says = when {
                name.isEmpty() -> "Property ${index + 1} has no name"
                !NAME.matches(name) -> "$name is not a usable property name"
                !seen.add(name) -> "$name is named twice"
                property.kind == PropertyKind.OBJECT && property.refObjectId == null ->
                    "$name says it is an object but does not say which"
                property.kind == PropertyKind.ARRAY && property.refObjectId == null && property.elementKind == null ->
                    "$name is an array but does not say of what"
                property.refObjectId != null && objects.findByIdOrNull(property.refObjectId)?.workspaceId != workspaceId ->
                    "$name points at an object this workspace does not have"
                else -> null
            }
            if (says != null) return ObjectValidationView(valid = false, message = says)
        }
        return ObjectValidationView(valid = true, message = "${properties.size} properties, all resolvable")
    }

    /**
     * What the editor sent, checked as it is turned into properties.
     *
     * Every reference is resolved here rather than trusted: a property naming
     * another workspace's object would let one workspace read the shape of
     * another's data. [self] is allowed through, because an object that refers
     * to itself is how a tree is described.
     */
    private fun propertiesOf(
        workspaceId: Long,
        sent: List<ObjectPropertyInput>,
        self: Long? = null,
    ): MutableList<ObjectProperty> {
        val seen = mutableSetOf<String>()
        return sent.map { property ->
            val name = property.name.trim()
            requireUsableName(name)
            if (!seen.add(name)) throw ObjectPropertyInvalidException("$name is named twice")

            property.refObjectId?.let { referenced ->
                if (referenced != self && objects.findByIdOrNull(referenced)?.workspaceId != workspaceId) {
                    throw ObjectPropertyInvalidException("$name points at an object this workspace does not have")
                }
            }
            if (property.kind == PropertyKind.OBJECT && property.refObjectId == null) {
                throw ObjectPropertyInvalidException("$name says it is an object but does not say which")
            }
            if (property.kind == PropertyKind.ARRAY && property.refObjectId == null && property.elementKind == null) {
                throw ObjectPropertyInvalidException("$name is an array but does not say of what")
            }

            ObjectProperty(
                name = name,
                kind = property.kind,
                refObjectId = property.refObjectId,
                elementKind = property.elementKind,
            )
        }.toMutableList()
    }

    private fun describe(held: WorkflowObject): ObjectView {
        val names = objects.findByWorkspaceId(held.workspaceId).associate { it.id to it.name }
        return ObjectView(
            id = requireNotNull(held.id),
            workspaceId = held.workspaceId,
            name = held.name,
            description = held.description,
            properties = held.properties.map { property ->
                ObjectPropertyView(
                    name = property.name,
                    kind = property.kind,
                    refObjectId = property.refObjectId,
                    elementKind = property.elementKind,
                    display = display(property, names),
                )
            },
            propertyCount = held.properties.size,
            createdAt = held.createdAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            createdBy = held.createdBy,
            lastModifiedAt = held.lastModifiedAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            lastModifiedBy = held.lastModifiedBy,
        )
    }

    /**
     * The type as the editor shows it — `string`, `ApiResponse`,
     * `array<FileObject>`. Worked out here so the screen never has to assemble
     * a type out of three columns, and so a reference reads as the name it has
     * now rather than the one it had when it was pointed at.
     */
    private fun display(property: ObjectProperty, names: Map<Long?, String>): String = when (property.kind) {
        PropertyKind.OBJECT -> names[property.refObjectId] ?: "(deleted object)"
        PropertyKind.ARRAY -> {
            val element = property.refObjectId
                ?.let { names[it] ?: "(deleted object)" }
                ?: property.elementKind?.name?.lowercase()
                ?: "?"
            "array<$element>"
        }
        else -> property.kind.name.lowercase()
    }

    /** A name a reference can point at: "oddly named" cannot. */
    private fun requireUsableName(name: String) {
        if (name.isEmpty()) throw ObjectNameInvalidException()
        if (!NAME.matches(name)) throw ObjectNameInvalidException(name)
    }

    private fun currentUser(): String =
        SecurityContextHolder.getContext().authentication?.name ?: "system"

    private fun requireWorkspaceAccess(workspaceId: Long) {
        val workspace = workspaces.findByIdOrNull(workspaceId) ?: throw WorkspaceNotFoundException(workspaceId)
        access.requireVisible(workspace)
    }

    private companion object {
        /** What can be written after a dot, which is how a property is read. */
        val NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
    }
}

data class CreateObjectInput(
    val workspaceId: Long,
    val name: String,
    val description: String? = null,
    val properties: List<ObjectPropertyInput>? = null,
)

data class UpdateObjectInput(
    val name: String? = null,
    val description: String? = null,
    /** Null leaves the properties alone; a list replaces them wholesale. */
    val properties: List<ObjectPropertyInput>? = null,
)

data class ObjectPropertyInput(
    val name: String,
    val kind: PropertyKind,
    val refObjectId: Long? = null,
    val elementKind: PropertyKind? = null,
)

data class ObjectPropertyView(
    val name: String,
    val kind: PropertyKind,
    val refObjectId: Long?,
    val elementKind: PropertyKind?,
    /** Ready to show: `string`, `ApiResponse`, `array<FileObject>`. */
    val display: String,
)

data class ObjectView(
    val id: Long,
    val workspaceId: Long,
    val name: String,
    val description: String?,
    val properties: List<ObjectPropertyView>,
    /** What the list's badge shows, so an empty object reads as empty. */
    val propertyCount: Int,
    val createdAt: String,
    val createdBy: String,
    val lastModifiedAt: String,
    val lastModifiedBy: String,
)

/** What Validate answers; it is a report, not a failure. */
data class ObjectValidationView(val valid: Boolean, val message: String)

data class ObjectPage(
    val content: List<ObjectView>,
    val page: Int,
    val size: Int,
    val totalElements: Int,
    val totalPages: Int,
) {
    constructor(page: Page<WorkflowObject>, describe: (WorkflowObject) -> ObjectView) : this(
        content = page.content.map(describe),
        page = page.number,
        size = page.size,
        totalElements = page.totalElements.toInt(),
        totalPages = page.totalPages,
    )
}

class ObjectNotFoundException(id: Long) : RuntimeException("No object with id $id")

class ObjectNameTakenException(name: String) : RuntimeException("This workspace already has an object called $name")

class ObjectNameInvalidException(name: String? = null) : RuntimeException(
    if (name == null) {
        "An object needs a name"
    } else {
        "$name cannot be used as a name: it has to start with a letter or underscore and hold only letters, digits and underscores"
    },
)

class ObjectPropertyInvalidException(says: String) : RuntimeException(says)

class ObjectInUseException(name: String, users: List<String>) : RuntimeException(
    "$name is used by ${users.joinToString(", ")}, so it cannot be deleted",
)
