package io.mszymanski.orknux.server.obj

import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.trigger.WorkflowTriggerRepository
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
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
    private val triggers: WorkflowTriggerRepository,
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
        val held = objects.findByIdOrNull(id)?.takeIf { access.canSee(it.workspaceId) }
            ?: throw ObjectNotFoundException(id)

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
     * An object another object or a webhook depends on is not one to delete.
     *
     * Two of the references to an object are guarded and the rest are not, and
     * that is a decision rather than an omission. An object is a *description of
     * a shape*, and most things naming one are reading the description:
     *
     * - Another object's property. Guarded, and always was: a property with a
     *   dangling reference cannot be shown, checked or offered — there is no
     *   degraded version of it to fall back to.
     * - A webhook's shape. Guarded here. Unlike the rest, this one is not read
     *   by a person — it is what every arriving request is checked against, and
     *   [WebhookAPI][io.mszymanski.orknux.server.trigger.WebhookAPI] answers 404
     *   when it resolves to nothing. A 404 is exactly what a path nobody listens
     *   on returns, so the webhook does not look broken to its caller; it looks
     *   absent, and the only record is a line in the firing log. `TriggerAPI`
     *   already refuses to *save* a webhook whose shape is missing, so deleting
     *   the object was the one door that could put a trigger into a state the
     *   trigger door itself would not accept.
     * - A function's parameter or return object, a tool's parameter, an object
     *   node on a canvas. **Not** guarded, deliberately. Each of these is an
     *   annotation: the shape tells the editor what to write above the code and
     *   what fields the picker downstream can offer. When it goes, the signature
     *   falls back to saying `object` and the picker shows nothing chosen — a
     *   thing somebody sees, on the screen where they would fix it, before
     *   anything runs. None of them reaches a published copy either: a
     *   snapshot's nodes carry `agentId`, `actionId` and `conditionId` and no
     *   object id at all, so there is no frozen copy going on naming one.
     *
     * The line, then, is not "id or name" and not "draft or published": it is
     * whether the reference is read by a person who can act on it, or consulted
     * by something that will quietly turn a caller away.
     */
    @MutationMapping
    @Transactional
    fun deleteObject(@Argument id: Long): Boolean {
        val held = objects.findByIdOrNull(id)?.takeIf { access.canSee(it.workspaceId) } ?: return false

        val users = objects.findByWorkspaceId(held.workspaceId)
            .filter { it.id != id && it.properties.any { property -> property.refObjectId == id } }
            .map { it.name } +
            triggers.findByObjectId(id).map { "the webhook ${it.name}" }
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
                (property.description?.trim()?.length ?: 0) > DESCRIPTION_LIMIT ->
                    "$name has a description longer than $DESCRIPTION_LIMIT characters"
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
                description = describedAs(name, property.description),
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
                    description = property.description,
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

    /**
     * A field's description, trimmed, or null where there is nothing to say.
     *
     * Refused rather than cut when it runs past the column. A description cut
     * to fit stops mid-sentence, and a sentence that stops mid-sentence is
     * worse than none: it is read as the whole of what the author meant, by a
     * person and by a model alike.
     */
    private fun describedAs(name: String, said: String?): String? {
        val trimmed = said?.trim()?.ifEmpty { null } ?: return null
        if (trimmed.length > DESCRIPTION_LIMIT) {
            throw ObjectPropertyInvalidException(
                "$name has a description longer than $DESCRIPTION_LIMIT characters",
            )
        }
        return trimmed
    }

    /** A name a reference can point at: "oddly named" cannot. */
    private fun requireUsableName(name: String) {
        if (name.isEmpty()) throw ObjectNameInvalidException()
        if (!NAME.matches(name)) throw ObjectNameInvalidException(name)
    }

    private fun currentUser(): String =
        SecurityContextHolder.getContext().authentication?.name ?: "system"

    private fun requireWorkspaceAccess(workspaceId: Long) {
        access.requireVisible(workspaceId)
    }

    private companion object {
        /** What can be written after a dot, which is how a property is read. */
        val NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")

        /**
         * How much a field may say about itself.
         *
         * The same bound every other description on this side carries. Prose
         * needs more room than a name, and it does not need an unbounded amount
         * of it: what a field means fits in a sentence or two, and anything
         * longer is documentation that belongs on the object rather than on one
         * of its fields.
         */
        const val DESCRIPTION_LIMIT = 500
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
    /** What the field means; blank is the same as saying nothing. */
    val description: String? = null,
)

data class ObjectPropertyView(
    val name: String,
    val kind: PropertyKind,
    val refObjectId: Long?,
    val elementKind: PropertyKind?,
    /** What the field means, as its author wrote it. */
    val description: String?,
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
