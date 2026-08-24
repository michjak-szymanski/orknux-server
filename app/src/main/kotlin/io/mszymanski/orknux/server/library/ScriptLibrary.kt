package io.mszymanski.orknux.server.library

import io.mszymanski.orknux.server.dependency.DependantView
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.OffsetDateTime

/**
 * A library loaded into this installation: JavaScript a script may import.
 *
 * **What a library is here, and why it is this.** It is a stored artefact — one
 * self-contained module, uploaded once by an administrator, kept as text in this
 * database. It is not a name from a registry and it is not a per-workspace upload,
 * and both of those were considered first.
 *
 * A registry name cannot work, because the only two ways to honour one are both
 * shut. Fetching at run time would mean giving the sandbox a network, which is the
 * thing `ScriptRunner` exists to withhold; fetching at upload time would mean this
 * server reaching out to a registry, which makes an offline installation
 * unusable and makes what a workspace runs depend on what a registry served that
 * afternoon. A version number does not fix the second: it narrows what may be
 * served without settling what was.
 *
 * And it is the installation's rather than a workspace's, because the question an
 * installation has to be able to answer is "what code is running in here". A
 * library every workspace could upload for itself would make that question have as
 * many answers as there are workspaces, and the answer administrators actually
 * need — *which* functions and tools depend on this thing — would be spread across
 * screens none of them can see. Loaded centrally, it is one list.
 *
 * A plugin is the exception, and deliberately: a plugin **embeds** its libraries
 * rather than importing them, because a plugin is meant to be portable between
 * installations and one that assumed a library was already loaded here would not
 * be. What that costs a plugin is the JavaScript a bundle needs — see
 * `PluginPermission`.
 */
@Entity
@Table(name = "script_library")
class ScriptLibrary(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    /**
     * What the library calls itself. Its identity, not the file's.
     *
     * Uploading the same key again replaces what is loaded under it, whatever the
     * file was named — which is how a library is updated in place without every
     * function that imports it having to be repointed.
     *
     * Taken from the filename rather than asked of the module. A library is
     * somebody else's code, very often a bundle nobody here wrote, and requiring it
     * to answer a question about itself would rule out every library worth loading.
     */
    @Column(name = "library_key", nullable = false, length = 64)
    var key: String,

    /** What it is called on screen. The key is what it *is*. */
    @Column(nullable = false, length = 200)
    var name: String,

    @Column(nullable = false, length = 255)
    var filename: String,

    /** The JavaScript that is evaluated. A module whose default export is imported. */
    @Column(nullable = false, columnDefinition = "text")
    var source: String,

    /**
     * What it was written in, when that was TypeScript.
     *
     * Never evaluated — [source] is what runs. Kept so the library can be
     * downloaded as the thing somebody wrote, the way a plugin's is.
     */
    @Column(columnDefinition = "text")
    var typescript: String? = null,

    @Column(name = "size_bytes", nullable = false)
    var sizeBytes: Long,

    /** Of the source text, so an unchanged re-upload is recognisable. */
    @Column(nullable = false, length = 64)
    var sha256: String,

    /**
     * What its default export turned out to hold, as JSON.
     *
     * Read once, when it is loaded, by evaluating it in the sandbox it will run in
     * and asking the exported value for its own members. The editor annotates
     * `imports` from this, so a call into a library is checked rather than
     * guessed at — and a library that cannot be evaluated at all is one this
     * finds out here, rather than at the moment a workflow needed it.
     *
     * Not a type declaration. Nothing in a bundle says what its arguments are, and
     * inventing an answer would be worse than admitting there is none: a member is
     * either something to call or something to read, and that is the whole of what
     * this claims.
     */
    @Column(name = "declared_members", nullable = false, columnDefinition = "text")
    var declaredMembers: String = "[]",

    /** Whether the default export is itself something to call, rather than an object. */
    @Column(nullable = false)
    var callable: Boolean = false,

    @Column(name = "uploaded_at", nullable = false)
    var uploadedAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "uploaded_by", nullable = false, length = 120)
    var uploadedBy: String = "",
)

interface ScriptLibraryRepository : JpaRepository<ScriptLibrary, Long> {

    fun findAllByOrderByNameAsc(): List<ScriptLibrary>

    /** By what it calls itself, which is what a re-upload replaces. */
    fun findByKey(key: String): ScriptLibrary?
}

/**
 * What a screen is told about a library.
 *
 * The source is not on it, for the reason a plugin's is not: a list of what is
 * loaded does not need the text, and a bundle can be a few hundred kilobytes.
 */
data class ScriptLibraryView(
    val id: Long,
    val key: String,
    val name: String,
    val filename: String,
    val sizeBytes: Double,
    val sha256: String,
    /** Whether it is something to call, rather than an object with members. */
    val callable: Boolean,
    val members: List<LibraryMemberView>,
    /**
     * What imports it, across every workspace.
     *
     * The whole reason a library is the installation's. An administrator deciding
     * whether to replace or remove one needs to know what depends on it, and that
     * is a question no workspace-level screen could answer.
     *
     * A [DependantView] and not a shape of this screen's own, because it is the
     * same row the delete refusal is worded from and the same row every other
     * component's Used by list draws — see
     * [ComponentDependants][io.mszymanski.orknux.server.dependency.ComponentDependants].
     * It carries the id, which is what #268 was about: a name in a sentence is
     * somewhere the reader has to go and find.
     */
    val usedBy: List<DependantView>,
    val uploadedAt: String,
    val uploadedBy: String,
)

/** One thing a library's default export holds. */
data class LibraryMemberView(val name: String, val callable: Boolean)

class LibraryNotFoundException(id: Long) : RuntimeException("There is no library $id")

class LibraryEmptyException : RuntimeException("That file is empty")

class LibraryTooLargeException(maxKb: Long) : RuntimeException("A library may be at most $maxKb KB")

class LibraryNotJavaScriptException(filename: String) :
    RuntimeException("$filename is not JavaScript; a library is a .js or .mjs file")

class LibraryNotTextException : RuntimeException("That file is not UTF-8 text")

/**
 * The filename does not make a name a script could import it by.
 *
 * Not because a workspace types this key — it does not; it chooses a local name of
 * its own. It is because the key is what an administrator matches a re-upload
 * against, and a key with a space or a slash in it is one nobody can say out loud.
 */
class LibraryKeyInvalidException(key: String) : RuntimeException(
    "\"$key\" cannot be a library name: it has to start with a letter and hold only " +
        "letters, digits, dots, dashes or underscores.",
)

/**
 * It could not be evaluated, or it exports nothing to import.
 *
 * The reason comes from the sandbox, because that sentence is what tells whoever
 * is loading it what is wrong with the file they chose.
 */
class LibraryUnreadableException(reason: String) :
    RuntimeException("That file could not be loaded as a library: $reason")

/**
 * A library something imports is not one to remove.
 *
 * Named rather than counted, and said with the workspace, because an administrator
 * removing a library is not the person who wrote the functions that use it — "4
 * functions" tells them nothing they can act on or pass on.
 */
class LibraryInUseException(users: List<String>) :
    RuntimeException("That library is imported by ${users.joinToString(", ")}")
