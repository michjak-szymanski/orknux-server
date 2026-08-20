package io.mszymanski.orknux.server.obj

import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Embeddable
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.OrderColumn
import jakarta.persistence.Table
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.time.OffsetDateTime

/**
 * The shape one property has.
 *
 * [OBJECT] names another object, and [ARRAY] holds many of something — either a
 * scalar or, again, another object. Keeping the reference as an id rather than a
 * name is what stops a rename from breaking every property that pointed at it.
 */
enum class PropertyKind {
    STRING,
    NUMBER,
    BOOLEAN,
    OBJECT,
    ARRAY,
}

/**
 * One property of an object: a name and a shape, and nothing about behaviour.
 *
 * An `@Embeddable` in an ordered collection rather than an entity of its own,
 * because a property has no life apart from the object holding it — the editor
 * saves the whole list, and a property nobody kept is one that is gone.
 */
@Embeddable
class ObjectProperty(
    @Column(nullable = false, length = 64)
    var name: String = "",

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var kind: PropertyKind = PropertyKind.STRING,

    /** The object this points at, when the kind is one or an array of them. */
    @Column(name = "ref_object_id")
    var refObjectId: Long? = null,

    /** What an array holds when it holds scalars; null when it holds objects. */
    @Enumerated(EnumType.STRING)
    @Column(name = "element_kind", length = 16)
    var elementKind: PropertyKind? = null,

    /**
     * What this field means, for whoever — or whatever — reads it.
     *
     * A name says what a field is called and nothing about what belongs in it,
     * which is a gap a person fills from context and a model fills by guessing.
     * This is where the context goes, so that both of them read the same
     * sentence rather than two different inferences from one word.
     */
    @Column(length = 500)
    var description: String? = null,
)

/**
 * A named data structure the workspace can point at.
 *
 * The reason it exists is references: a field a node points at is unchecked and
 * unofferable until something knows what the event carries. An
 * object is that something, and one definition serves a trigger's output, a
 * function's argument and an agent's structured answer alike.
 *
 * Fields only. The name is deliberately not "record": an object with members
 * can grow methods later without the concept having to be renamed, and a record
 * that grew them would be a contradiction.
 */
@Entity
@Table(name = "workflow_object")
class WorkflowObject(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "workspace_id", nullable = false)
    val workspaceId: Long,

    @Column(nullable = false, length = 64)
    var name: String,

    @Column
    var description: String? = null,

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "object_property", joinColumns = [JoinColumn(name = "object_id")])
    @OrderColumn(name = "position")
    var properties: MutableList<ObjectProperty> = mutableListOf(),

    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "created_by", nullable = false, length = 255)
    val createdBy: String = "",

    @Column(name = "last_modified_at", nullable = false)
    var lastModifiedAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "last_modified_by", nullable = false, length = 255)
    var lastModifiedBy: String = "",
)

interface WorkflowObjectRepository : JpaRepository<WorkflowObject, Long> {

    fun findByWorkspaceId(workspaceId: Long, pageable: Pageable): Page<WorkflowObject>

    fun findByWorkspaceId(workspaceId: Long): List<WorkflowObject>

    fun findByWorkspaceIdAndName(workspaceId: Long, name: String): WorkflowObject?
}
