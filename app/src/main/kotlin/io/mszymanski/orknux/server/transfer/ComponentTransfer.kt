package io.mszymanski.orknux.server.transfer

/**
 * Moving a workspace's components between installations as JSON.
 *
 * The file this package writes and reads is a *contract*, not a dump: it is
 * written by one installation and read by another, possibly a release or two
 * apart, and the two have nothing else in common. Three rules follow from that
 * and are worth stating once, here, rather than being rediscovered:
 *
 * 1. **No ids travel.** An id is a number this database printed and it means
 *    nothing anywhere else. Everything the envelope points at, it points at by
 *    name — and because every reference in it is single-kinded (a property can
 *    only name an object, a condition's member can only name a condition) a
 *    reference is a bare name rather than a kind-and-name pair.
 * 2. **No secrets travel.** A variable's value is encrypted at rest and is not
 *    ours to put in a file somebody emails. So a function that is handed a
 *    variable exports the variable's *name*, and the import refuses until the
 *    target workspace has one of its own by that name.
 * 3. **A version the reader does not know is refused, by name.** Reading what
 *    can be read out of a newer envelope is how half a workflow gets created
 *    and nobody is told which half.
 *
 * The envelope is written and read by hand, as `WorkflowSnapshot` writes the
 * published graph, and for the same reason: a shape in a file outlives the class
 * it came from, and binding it by reflection makes every field rename a silent
 * format change.
 */

/**
 * The contract this installation writes, and the newest one it will read.
 *
 * An integer, and only ever compared with `>` — an envelope claiming a version
 * this does not know is refused rather than partially read. Bump it when the
 * *meaning* of something in the envelope changes; adding a field an older
 * reader can ignore is not a bump.
 */
const val COMPONENT_FORMAT_VERSION: Int = 1

/**
 * What can be exported and imported.
 *
 * The self-contained half of the catalogue: everything here reaches only other
 * things in here. Agents and workflows are not on the list yet — they reach
 * models and connections, which hold credentials, and that needs the import to
 * ask a question this one never has to.
 */
enum class ComponentKind {
    OBJECT,
    FUNCTION,
    CONDITION,
    TOOL,
    SKILL,
    ;

    /** "function", for a message somebody reads. */
    val label: String get() = name.lowercase()

    /**
     * "an object", "a function".
     *
     * Here rather than at each message, because there is exactly one of these
     * that takes "an" and every sentence that names a kind has to get it right.
     */
    val indefinite: String get() = if (this == OBJECT) "an $label" else "a $label"
}

/**
 * How much of what a component reaches travels with it.
 *
 * [DEEP] is the useful default: a function exported without the objects it is
 * typed against lands somewhere it cannot be opened. [SHALLOW] is for moving one
 * thing into a workspace that already has the rest, and the difference is
 * entirely in what the envelope carries — the import behaves the same either
 * way, resolving whatever is not in the file against the target workspace.
 */
enum class ExportDepth {
    SHALLOW,
    DEEP,
}

/** What the import will do about one thing the envelope mentions. */
enum class ImportDisposition {

    /** Created here under the name the envelope gave it. */
    CREATE,

    /**
     * Created here under a different name, because that one was taken.
     *
     * Never a replacement: somebody else's work under the same name is theirs.
     * Everything else in the same envelope that pointed at this points at the
     * renamed one, so the import is internally consistent even when half of it
     * has been renamed.
     */
    RENAME,

    /**
     * Not created: the workspace already has one by this name and the envelope
     * did not carry it. A shallow export's dependencies and every variable land
     * here.
     */
    REUSE,

    /**
     * Pointed at, not carried, and not here. The import is refused while any of
     * these remain, because creating the rest would leave a function typed
     * against an object that does not exist.
     */
    MISSING,
}

/** The envelope is not JSON, or not this format at all. */
class EnvelopeUnreadableException(reason: String) :
    RuntimeException("This file is not an Orknux export: $reason")

/**
 * The envelope was written by a newer Orknux than this one.
 *
 * Names both versions and refuses. The alternative — reading the fields it
 * recognises — creates a component that is missing whatever the new version
 * added, with nothing to say which parts arrived.
 */
class EnvelopeVersionUnknownException(found: Int, producedBy: String?) : RuntimeException(
    "This export is format version $found and this installation reads version " +
        "$COMPONENT_FORMAT_VERSION" +
        (producedBy?.let { ". It was produced by $it" } ?: "") +
        ". Upgrade this installation, or export again from an installation of this version.",
)

/** The envelope is well-formed but says something impossible. */
class EnvelopeInvalidException(says: String) : RuntimeException(says)

/**
 * The import was asked to go ahead while something it needs is not here.
 *
 * The preview says the same thing first, so this is the answer to somebody who
 * confirmed anyway — or to a client that never asked. Nothing is written.
 */
class ImportNotPossibleException(problems: List<String>) : RuntimeException(
    "Nothing was imported. " + problems.joinToString(" "),
)
