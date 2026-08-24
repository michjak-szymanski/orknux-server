package io.mszymanski.orknux.server.library

import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * A library, as whoever is importing it needs to see it.
 *
 * The editors ask for this and the admin screen asks for it, so it is one class
 * rather than a reader in each: what a library holds is read out of one column of
 * JSON, and two readers of one column eventually disagree about what an absent
 * field meant.
 *
 * Read with the tree API rather than bound to a data class, like everything else
 * that reads JSON here — there is no Jackson Kotlin module on the classpath.
 */
@Component
class LibraryImports(
    private val libraries: ScriptLibraryRepository,
    private val mapper: ObjectMapper,
) {

    /**
     * The library with this id, or a refusal.
     *
     * No workspace in the question. A library is the installation's, loaded once by
     * an administrator, and there is no workspace on the row to compare against —
     * what a workspace decides is only whether to import it and what to call it.
     */
    fun require(id: Long): ScriptLibrary = libraries.findById(id).orElseThrow { LibraryNotFoundException(id) }

    /** The same, answering null rather than throwing, for a view being drawn. */
    fun find(id: Long): ScriptLibrary? = libraries.findById(id).orElse(null)

    /**
     * What its default export holds, as it was read when the library was loaded.
     *
     * A member is something to call or something to read, and that is the whole of
     * the claim: nothing in a bundle says what its arguments are, and inventing an
     * answer would be worse than admitting there is none.
     */
    fun membersOf(library: ScriptLibrary): List<LibraryMemberView> =
        mapper.readTree(library.declaredMembers).values().map { held ->
            LibraryMemberView(
                name = held.path("name").asString(""),
                callable = held.path("callable").asBoolean(false),
            )
        }

    fun viewOf(library: ScriptLibrary): ImportedLibraryView = ImportedLibraryView(
        key = library.key,
        callable = library.callable,
        members = membersOf(library),
    )
}

/**
 * What an imported library is, as far as the importer needs to know.
 *
 * Enough for the editor to annotate `imports` with, and to say in a panel what was
 * imported. Not the source: whoever is reading this is writing a call into the
 * library, not reading the library.
 */
data class ImportedLibraryView(
    val key: String,
    /** Whether the import is itself something to call, rather than an object. */
    val callable: Boolean,
    val members: List<LibraryMemberView>,
)
