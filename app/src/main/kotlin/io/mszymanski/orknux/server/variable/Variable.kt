package io.mszymanski.orknux.server.variable

import io.mszymanski.orknux.connector.security.SECRET_COLUMN_LENGTH
import io.mszymanski.orknux.connector.security.SecretConverter
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.time.OffsetDateTime

/**
 * What a variable holds.
 *
 * Scalars only. A shape belongs in the object catalogue, and a variable is one
 * value handed to a function as one argument — an object here would mean a
 * second way of describing shapes, which is one too many.
 */
enum class VariableType {
    STRING,
    NUMBER,
    BOOLEAN,
}

/**
 * Whether a variable is something to keep out of sight.
 *
 * Both are encrypted at rest and both are handed to functions the same way; what
 * differs is the screen. A [VALUE] is read with the list, because hiding a
 * channel name or a threshold only makes it awkward to work with. A [SECRET] is
 * shown only when somebody asks, and the audit log records that they did.
 */
enum class VariableKind {
    VALUE,
    SECRET,
}

/**
 * A folder of variables.
 *
 * Its own table rather than a label, for the reason a skill catalog is one: the
 * screen lists catalogs beside the variables of the one selected, so a catalog is a
 * thing that exists, and has a count worth showing, before anything is in it.
 */
@Entity
@Table(name = "variable_catalog")
class VariableCatalog(
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

interface VariableCatalogRepository : JpaRepository<VariableCatalog, Long> {

    fun findByWorkspaceIdOrderByNameAsc(workspaceId: Long): List<VariableCatalog>

    fun findByWorkspaceIdAndName(workspaceId: Long, name: String): VariableCatalog?
}

/**
 * A named value the workspace keeps, for the things that need one.
 *
 * A function checking a webhook's signature needs the secret to check against.
 * Before this the only places to put one were the source — readable by anyone
 * who can open the editor, and copied into every function that needs it — or a
 * node's parameter, where it travels through a graph in plain sight.
 *
 * Stored the way every other credential here is: encrypted at rest, and never
 * sent back to a screen. What a screen gets is the name, the type, and whether
 * a value has been set — enough to manage them, not enough to read them.
 */
@Entity
@Table(name = "workspace_variable")
class WorkspaceVariable(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "workspace_id", nullable = false)
    val workspaceId: Long,

    /** Which catalog holds it; every variable is in one. */
    @Column(name = "catalog_id", nullable = false)
    var catalogId: Long,

    @Column(nullable = false, length = 64)
    var name: String,

    /** What it is for; a name that has to be an identifier cannot say much. */
    @Column(length = 500)
    var description: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var type: VariableType = VariableType.STRING,

    /** Whether it may be read from the list, or only on request; see [VariableKind]. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var kind: VariableKind = VariableKind.SECRET,

    /**
     * Encrypted in the database; see `SecretCipher`.
     *
     * Held as text whatever the type says, because that is what the column is.
     * The type decides how it is written when a function is handed it, which is
     * the only moment the difference matters.
     */
    @Convert(converter = SecretConverter::class)
    @Column(length = SECRET_COLUMN_LENGTH)
    var value: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),

    /** Who put it there; the person who knows what it is for. */
    @Column(name = "created_by", nullable = false, length = 120)
    val createdBy: String = "",

    @Column(name = "last_modified_at", nullable = false)
    var lastModifiedAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "last_modified_by", nullable = false, length = 120)
    var lastModifiedBy: String = "",
)

interface WorkspaceVariableRepository : JpaRepository<WorkspaceVariable, Long> {

    fun findByWorkspaceIdOrderByNameAsc(workspaceId: Long, pageable: Pageable): Page<WorkspaceVariable>

    fun findByCatalogIdOrderByNameAsc(catalogId: Long, pageable: Pageable): Page<WorkspaceVariable>

    fun findByCatalogIdAndNameContainingIgnoreCaseOrderByNameAsc(
        catalogId: Long,
        name: String,
        pageable: Pageable,
    ): Page<WorkspaceVariable>

    /** What the count badge on a catalog shows. */
    fun countByCatalogId(catalogId: Long): Long

    fun findByCatalogId(catalogId: Long): List<WorkspaceVariable>

    /** The list's search box: by name, since the value is not something to search. */
    fun findByWorkspaceIdAndNameContainingIgnoreCaseOrderByNameAsc(
        workspaceId: Long,
        name: String,
        pageable: Pageable,
    ): Page<WorkspaceVariable>

    fun findByCatalogIdAndName(catalogId: Long, name: String): WorkspaceVariable?

    fun findByWorkspaceId(workspaceId: Long): List<WorkspaceVariable>
}

class VariableNotFoundException(id: Long) : RuntimeException("No variable with id $id")

class VariableNameTakenException(name: String, catalog: String) :
    RuntimeException("$catalog already holds a variable named \"$name\"")

class VariableNameInvalidException(name: String) : RuntimeException(
    "\"$name\" cannot be a variable name. A name is letters, digits and underscores, starting with " +
        "a letter — a function receives it as an argument, and an argument has to be nameable.",
)

class VariableCatalogNotFoundException(id: Long) : RuntimeException("No catalog with id $id")

class VariableCatalogNameTakenException(name: String) :
    RuntimeException("A catalog named \"$name\" already exists in this workspace")

class VariableCatalogNameInvalidException : RuntimeException("A catalog name is required")

class VariableCatalogNotEmptyException(name: String, held: Long) : RuntimeException(
    "$name still holds $held ${if (held == 1L) "variable" else "variables"}. " +
        "Move or remove them first; a catalog is a folder, and emptying it is a decision about its contents.",
)

class VariableInUseException(name: String, functions: List<String>) : RuntimeException(
    "\"$name\" is an external parameter of ${functions.joinToString(", ")}. " +
        "Take it off those functions first; removing it here would change what they are handed.",
)

/**
 * A variable something reads a credential from cannot be deleted.
 *
 * Refused rather than allowed with the holder reporting a broken reference,
 * which was the other way it could have gone. Removing an MCP server entry is
 * ordinary housekeeping — the server is somebody else's and may genuinely be
 * gone — so #170 let that through and reported it. A credential is this
 * installation's own, nothing about the thing reading it has stopped existing,
 * and the only thing a delete accomplishes is taking it offline at some later
 * moment nobody will connect to this. Renaming and moving are free, because the
 * reference is by id, so this refuses the one operation that actually destroys
 * something, and it names what is holding on.
 *
 * @param readers each already worded as a noun phrase — "the model provider
 *   Shared OpenAI", "the connection Slack" — because a list of bare names
 *   across three kinds of holder is a puzzle rather than an answer.
 */
class VariableHeldAsCredentialException(name: String, readers: List<String>) : RuntimeException(
    "\"$name\" is the credential of ${readers.joinToString(", ")}. " +
        "Give ${if (readers.size == 1) "it" else "them"} a value of " +
        "${if (readers.size == 1) "its" else "their"} own, or point at another secret, first — removing it " +
        "here would leave nothing to authenticate with.",
)

/**
 * And it cannot stop being a secret while something reads it.
 *
 * A [VariableKind.VALUE] is returned with the listing, so turning a bound
 * variable into one would put an API key on every member's screen. The same
 * rule refuses the binding in the first place; this is the other end of it.
 */
class VariableSecrecyHeldException(name: String, readers: List<String>) : RuntimeException(
    "\"$name\" is the credential of ${readers.joinToString(", ")}, so it has to stay a secret. " +
        "A value is read with the list, and a key on a list is a key on a screen.",
)
