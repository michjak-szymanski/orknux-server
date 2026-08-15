package io.mszymanski.gyloli.workflow.health

/** What a reachability check found, worded so it can be shown as it is. */
data class Reachability(val reachable: Boolean, val detail: String)

/**
 * Something the platform needs to be up. Implemented by whatever holds the
 * connection to it, so a dependency appears on the monitoring screen by
 * existing rather than by being added to it in two places.
 */
interface ServiceHealth {

    /** As it should be shown; the name of the service, not of the bean. */
    val service: String

    fun reachable(): Reachability
}
