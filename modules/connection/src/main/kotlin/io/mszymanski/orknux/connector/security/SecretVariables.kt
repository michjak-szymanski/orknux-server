package io.mszymanski.orknux.connector.security

/**
 * The named secrets a workspace keeps, for the things in this module that may be
 * pointed at one instead of holding a copy.
 *
 * A workspace variable belongs to the application rather than to this module, so
 * this is the interface that keeps the dependency pointing the right way - the
 * same arrangement `WorkspaceDirectory` is. What the module needs to know about
 * a variable is small and is all here: enough to name it on a screen, to refuse
 * to bind one that is not kept out of sight, and to read it when a call is about
 * to be made.
 *
 * Answering is a read of one row by id. Deliberately not a list: nothing in this
 * module has any business enumerating a workspace's secrets, and an interface
 * that offered it would be an interface somebody eventually used.
 */
fun interface SecretVariables {

    /**
     * The variable with this id, if the workspace has one.
     *
     * Null covers both an id that is nothing and an id that belongs to another
     * workspace, because the caller may do the same thing about either and
     * telling them apart would make guessing at ids a way to learn what another
     * workspace holds.
     */
    fun find(workspaceId: Long, variableId: Long): HeldSecret?
}

/**
 * One workspace variable, as this module sees it.
 *
 * Not a data class, and [toString] does not carry [value]. A credential must
 * never reach a log readable or not, and a generated `toString` puts one in
 * every log line that ever interpolates the object - including the ones written
 * by frameworks nobody here controls.
 */
class HeldSecret(
    val id: Long,
    val name: String,
    /** Which catalog holds it. Names are unique per catalog, not per workspace. */
    val catalog: String,
    /** Whether it is one of the ones kept out of sight; only those may be bound. */
    val secret: Boolean,
    /**
     * What it holds, out of the envelope, or null when nothing has been put in
     * it yet. Still worth checking with [SecretCipher.isEncrypted]: a value
     * written with a key this installation no longer has comes back sealed.
     */
    val value: String?,
) {

    override fun toString(): String = "HeldSecret($catalog/$name)"
}
