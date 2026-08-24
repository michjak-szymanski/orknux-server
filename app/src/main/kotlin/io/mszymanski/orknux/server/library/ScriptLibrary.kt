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
 * **A package may nonetheless be the way the file arrives**, and since #265 it can
 * be. `NpmRegistry` fetches one, once, when an administrator asks for it by name
 * and exact version, on the server and into this table — and the row is the same
 * row an upload makes. Nothing about the paragraph above is walked back: the
 * sandbox still has no network, an installation with no registry configured still
 * has only the upload, and what runs is still the stored artefact and not a name.
 * What the registry buys is not having to find the bundle by hand. What it costs
 * is written down: [origin] and the four columns under it say which package,
 * which version, from where, and what it hashed to.
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

    /**
     * How the file got here: `UPLOAD` or `REGISTRY`.
     *
     * A column rather than "the package columns are filled in", because the two
     * are not the same question. A row that came from a registry and lost its
     * provenance would read as an upload, and an upload is the one thing an
     * installation can say nothing further about — so which of the two this is
     * has to be stated rather than inferred from what happens to be null.
     */
    @Column(nullable = false, length = 16)
    var origin: String = ORIGIN_UPLOAD,

    /** The package it was fetched from, scope and all. Null for an upload. */
    @Column(name = "origin_package", length = 214)
    var originPackage: String? = null,

    /**
     * The version that was fetched, as the registry resolved it.
     *
     * Exact, always: a range and `latest` are refused before anything is
     * fetched, because a specification that resolves differently tomorrow is not
     * an answer to what code is running here.
     */
    @Column(name = "origin_version", length = 64)
    var originVersion: String? = null,

    /** The file that was downloaded, so the fetch can be repeated and compared. */
    @Column(name = "origin_url", length = 500)
    var originUrl: String? = null,

    /**
     * What the registry said that file hashes to, verified against what arrived.
     *
     * The registry's own claim, in its own spelling — `sha512-…`. Kept as the
     * claim rather than as a hash of our own, because what it is good for is
     * being compared with the same claim somewhere else. [sha256] is the other
     * half and is over the stored text.
     */
    @Column(name = "origin_integrity", length = 160)
    var originIntegrity: String? = null,

    /**
     * Which file inside the package this is.
     *
     * A package ships several builds and exactly one of them is running here.
     * Without this the row names a version and still cannot say what it holds.
     */
    @Column(name = "origin_entry", length = 255)
    var originEntry: String? = null,
) {
    companion object {
        const val ORIGIN_UPLOAD = "UPLOAD"
        const val ORIGIN_REGISTRY = "REGISTRY"
    }
}

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
    /**
     * Where it was fetched from, or null when somebody uploaded the file.
     *
     * Null is the honest answer for an upload rather than an awkward one: an
     * uploaded file has no provenance this installation can vouch for, and
     * inventing a row of blanks for it would read as though it had.
     */
    val registry: LibraryRegistryView?,
)

/**
 * What a registry served, and what it claimed about it.
 *
 * Shown rather than kept, because the point of recording a fetch is that somebody
 * can check it: the same package at the same version fetched anywhere else has the
 * same integrity string, and a row that disagrees is worth knowing about.
 */
data class LibraryRegistryView(
    /** The npm package, scope and all. */
    val packageName: String,
    /** Exactly one version. Never a range and never a tag. */
    val version: String,
    val url: String,
    /** The registry's own hash of the file it served, verified on arrival. */
    val integrity: String,
    /** Which file inside the package is the one that runs. */
    val entry: String,
)

/** Whether this installation can fetch a package, and from where. */
data class LibraryRegistryStatus(
    /** False where no registry is configured: the screen offers the upload alone. */
    val configured: Boolean,
    /** Where packages would come from. Empty when there is none. */
    val url: String,
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

/** Nothing to fetch from: this installation was configured without a registry. */
class LibraryRegistryOffException : RuntimeException(
    "This installation fetches no packages. Upload the file instead, or set ORKNUX_LIBRARY_REGISTRY_URL.",
)

/**
 * What was typed is not a package and an exact version.
 *
 * The reason is carried rather than reworded, because there are two of them —
 * the name is not a name, or the version is not one version — and they want
 * different things done about them.
 */
class LibraryPackageInvalidException(spec: String, reason: String) :
    RuntimeException("\"$spec\" cannot be installed: $reason")

class LibraryPackageMissingException(spec: String) :
    RuntimeException("The registry has no $spec")

/** The registry could not be reached at all. Said plainly, with the way out. */
class LibraryRegistryUnreachableException(spec: String, reason: String) : RuntimeException(
    "$spec could not be fetched: $reason. Upload the file instead if this installation has no way out.",
)

/** It answered, and what it answered cannot be used. */
class LibraryRegistrySilentException(spec: String, reason: String) :
    RuntimeException("$spec could not be fetched: $reason")

/**
 * What arrived is not what the registry said it would be.
 *
 * Both hashes are named. A mismatch is either a mirror serving something else or
 * a download that went wrong, and which of the two it is starts with being able
 * to say what was actually received.
 */
class LibraryIntegrityException(spec: String, expected: String, actual: String) : RuntimeException(
    "$spec was refused: the registry said the file would be $expected and it was $actual",
)

/**
 * The package ships no single ES module for this to install.
 *
 * Refused rather than half-installed. A package whose only build is CommonJS
 * evaluates to `module is not defined` in the sandbox, which is a true sentence
 * that tells nobody what to do next; this one does.
 */
class LibraryNotAModuleException(spec: String) : RuntimeException(
    "$spec ships no ES module to install: this needs one self-contained file, the kind `module` or " +
        "`exports` points at. Build a bundle and upload it.",
)

/**
 * It is a module, and it is not on its own.
 *
 * **The whole answer on dependencies, in one sentence to whoever asked.** This
 * installation does not bundle: assembling a package's dependency graph here
 * would produce an artefact no registry published and nobody can compare against
 * anything, which is the opposite of what the columns beside it are for. So a
 * package that imports is refused, by name, with the way out — a bundle built
 * where bundles are built, and uploaded.
 */
class LibraryDependsException(spec: String, entry: String, imported: String) : RuntimeException(
    "$spec was not installed: its $entry imports \"$imported\", and a library has to be one self-contained " +
        "file. This installation does not bundle. Build a bundle and upload it.",
)
