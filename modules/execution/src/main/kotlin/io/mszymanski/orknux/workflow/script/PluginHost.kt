package io.mszymanski.orknux.workflow.script

/**
 * What a plugin may ask the *server* to do on its behalf.
 *
 * Distinct from [PluginPermission] on purpose, and the distinction is the whole
 * point of having two lists. A permission turns on a language builtin and
 * nothing else — that file says so, and says that its vocabulary cannot express
 * "give me a socket". This one can, so it is written down somewhere else, granted
 * separately, and shown separately to whoever accepts a plugin.
 *
 * **Nothing here opens the sandbox.** A capability is a function the server
 * implements and hands in as a callable; the plugin never gets a client, a
 * credential or a socket, and `allowIO`, `allowHostAccess` and the rest stay the
 * denials they are. What crosses is an argument and an answer, both as data.
 *
 * The reason it exists at all: a plugin's whole job is to know one outside
 * service well, and there are questions about that service — what is in this
 * Slack thread — that cannot be answered from the payload alone. The choice is
 * between the server making that call under a named grant and the feature not
 * existing. Issue #316.
 */
enum class PluginCapability(
    /** What it gives, as a person deciding whether to accept a plugin reads it. */
    val summary: String,
) {

    /**
     * Read a thread from one of the workspace's Slack connections.
     *
     * Read-only, and only through a connection the plugin was pointed at: the
     * handle comes from a `connection` parameter somebody filled in, so a plugin
     * granted this can still only reach the Slack a workspace handed it.
     */
    SLACK_READ_THREAD("Read a Slack thread, through a connection it was given"),

    ;

    companion object {

        /** The one named that, or null. Refused on upload rather than half-granted. */
        fun named(what: String): PluginCapability? =
            entries.firstOrNull { it.name.equals(what.trim(), ignoreCase = true) }
    }
}

/**
 * The server, as a plugin may ask things of it.
 *
 * One method per capability, and the argument and the answer are both JSON text
 * — which is what keeps this a door rather than a hole: nothing on either side
 * of it is a host object, so there is no reflection to reach through and no type
 * to walk from.
 *
 * Implemented in the app, where the connections and their credentials are, and
 * handed to [PluginRunner] so the execution module goes on knowing nothing about
 * Slack.
 */
fun interface PluginHost {

    /**
     * @param capability what is being asked for, already checked against what the
     *   plugin was granted.
     * @param argument the call's arguments, as a JSON array.
     * @return the answer as JSON, or a JSON object with an `error` on it. A
     *   refusal is data rather than an exception because the plugin has to be
     *   able to say something useful about it: "that connection is gone" is a
     *   sentence a workflow can act on.
     */
    fun ask(capability: PluginCapability, argument: String): String
}
