package io.mszymanski.orknux.server.transfer

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.OffsetDateTime

/**
 * An export the installation keeps, under a name.
 *
 * There is no second format here and there is deliberately no second table of
 * components. A template *is* the envelope [ComponentExporter] writes, held as
 * text with a name and a description beside it, and "Use template" is
 * [ComponentImporter] pointed at this row instead of at an upload. Everything
 * the file format already settled — no ids, no secrets, rename on collision,
 * refuse a version this installation does not read — is therefore settled here
 * too, and cannot drift apart from it.
 *
 * Two things this row does not hold, and both are decisions rather than
 * oversights.
 *
 * **No pointer back to what it was made from.** Not the workspace, not the
 * function's id. A template is a copy taken at a moment; editing the function it
 * was taken from does not change it. A column naming the original would be read
 * as a link by everybody who saw it, and the page would then have to explain
 * that the link does not mean what links mean.
 *
 * **No summary of what is inside.** How many components, which kinds, which
 * format version: all of that is in the envelope, and reading it out of the
 * envelope is one small JSON parse on one administrator's screen. A copy in
 * columns would be a second answer, and the moment somebody replaces the file it
 * would be the wrong one.
 */
@Entity
@Table(name = "component_template")
class ComponentTemplate(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    /** Unique installation-wide: one list, that everybody reads. */
    @Column(nullable = false, length = 120)
    var name: String,

    @Column(length = 1000)
    var description: String? = null,

    /**
     * The envelope, exactly as it was exported or uploaded.
     *
     * Kept byte for byte rather than reformatted. What is stored is what was
     * validated, so a template that read cleanly on the day it was published
     * cannot be changed into one that does not by anything on this side.
     */
    @Column(nullable = false, columnDefinition = "text")
    var envelope: String,

    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "created_by", nullable = false, length = 255)
    val createdBy: String = "",

    @Column(name = "last_modified_at", nullable = false)
    var lastModifiedAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "last_modified_by", nullable = false, length = 255)
    var lastModifiedBy: String = "",
)

interface ComponentTemplateRepository : JpaRepository<ComponentTemplate, Long> {

    fun findByNameIgnoreCase(name: String): ComponentTemplate?

    fun findAllByOrderByNameAsc(): List<ComponentTemplate>
}
