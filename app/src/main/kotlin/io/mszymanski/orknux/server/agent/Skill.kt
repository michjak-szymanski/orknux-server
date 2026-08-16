package io.mszymanski.orknux.server.agent

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.time.OffsetDateTime

/**
 * A reusable instruction set that guides how an agent goes about something.
 *
 * The content is markdown opening with a frontmatter block — a `---` fence
 * naming and describing the skill — which is what makes a skill something an
 * agent can be handed rather than a note somebody left. [SkillFormat] is where
 * that shape is checked.
 */
@Entity
@Table(name = "agent_skill")
class AgentSkill(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "workspace_id", nullable = false)
    val workspaceId: Long,

    /** The folder it lives in; every skill is in one, the way a memory is. */
    @Column(name = "catalog_id", nullable = false)
    var catalogId: Long,

    @Column(nullable = false, length = 120)
    var name: String,

    @Column(length = 500)
    var description: String? = null,

    @Column(nullable = false, columnDefinition = "text")
    var content: String,

    @Column(nullable = false)
    var enabled: Boolean = true,

    @Column(name = "last_modified_at", nullable = false)
    var lastModifiedAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "last_modified_by", nullable = false, length = 120)
    var lastModifiedBy: String = "",
)

interface AgentSkillRepository : JpaRepository<AgentSkill, Long> {

    fun findByWorkspaceId(workspaceId: Long, pageable: Pageable): Page<AgentSkill>

    fun findByCatalogId(catalogId: Long, pageable: Pageable): Page<AgentSkill>

    fun findByCatalogId(catalogId: Long): List<AgentSkill>

    fun countByCatalogId(catalogId: Long): Long

    fun deleteByCatalogId(catalogId: Long)

    fun findByWorkspaceIdAndName(workspaceId: Long, name: String): AgentSkill?
}

/**
 * A folder of skills.
 *
 * Its own table rather than a label, for the reason a memory catalog is one: the
 * screen lists catalogs beside the skills of the one selected, so a catalog is a
 * thing that exists, and has a count worth showing, before anything is in it.
 * It is also the unit an agent is granted.
 */
@Entity
@Table(name = "skill_catalog")
class SkillCatalog(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "workspace_id", nullable = false)
    val workspaceId: Long,

    @Column(nullable = false, length = 120)
    var name: String,

    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "created_by", nullable = false, length = 120)
    val createdBy: String = "",
)

interface SkillCatalogRepository : JpaRepository<SkillCatalog, Long> {

    fun findByWorkspaceIdOrderByNameAsc(workspaceId: Long): List<SkillCatalog>

    fun findByWorkspaceIdAndName(workspaceId: Long, name: String): SkillCatalog?
}

/** Whether a piece of content is shaped like a skill, and where it is not. */
data class FormatCheck(val valid: Boolean, val message: String? = null, val line: Int? = null)

/**
 * The frontmatter a skill opens with.
 *
 * A skill is handed to an agent, so it has to say what it is called and what it
 * is for in a place that can be read without reading the whole of it. That is
 * the fenced block at the top; everything after it is the skill itself.
 */
object SkillFormat {

    private const val FENCE = "---"

    fun check(content: String): FormatCheck {
        val lines = content.lines()
        val first = lines.indexOfFirst { it.isNotBlank() }
        if (first == -1) return FormatCheck(false, "A skill needs a frontmatter block and a body", 1)
        if (lines[first].trim() != FENCE) {
            return FormatCheck(false, "A skill opens with a $FENCE frontmatter fence", first + 1)
        }

        val closing = lines.drop(first + 1).indexOfFirst { it.trim() == FENCE }
        if (closing == -1) return FormatCheck(false, "The frontmatter fence is never closed", first + 1)

        val frontmatter = lines.subList(first + 1, first + 1 + closing)
        for (field in listOf("name", "description")) {
            val entry = frontmatter.firstOrNull { it.trimStart().startsWith("$field:") }
                ?: return FormatCheck(false, "The frontmatter has no $field", first + 1)
            if (entry.substringAfter("$field:").isBlank()) {
                return FormatCheck(false, "The frontmatter $field is empty", first + 2 + frontmatter.indexOf(entry))
            }
        }

        val body = lines.drop(first + closing + 2)
        if (body.all { it.isBlank() }) {
            return FormatCheck(false, "The skill has frontmatter but no body", first + closing + 2)
        }
        return FormatCheck(true)
    }

    /** What a new skill starts as: the shape, with the parts named. */
    fun starter(name: String, description: String?): String = """
        $FENCE
        name: $name
        description: ${description ?: "What this skill is for."}
        $FENCE

        # $name

        ## Objective

        What an agent following this is trying to achieve.

        ## Steps

        - The first thing to do
    """.trimIndent()
}

class SkillNotFoundException(id: Long) : RuntimeException("No skill with id $id")

class SkillCatalogNotFoundException(id: Long) : RuntimeException("No skill catalog with id $id")

class SkillCatalogNameTakenException(name: String) :
    RuntimeException("This workspace already has a skill catalog called $name")

class SkillCatalogNameInvalidException : RuntimeException("A skill catalog needs a name")

class SkillNameTakenException(name: String) :
    RuntimeException("A skill named \"$name\" already exists in this workspace")

class SkillNameInvalidException : RuntimeException("A skill name is required")

class SkillContentInvalidException(reason: String) : RuntimeException(reason)
