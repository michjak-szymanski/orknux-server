package io.mszymanski.gyloli.connector.connection

/**
 * Which teams exist. This module holds connections for a team but not the team
 * itself, so handing a new default to every existing team means asking whoever
 * owns them — the interface is what keeps this module from depending on that.
 */
interface TeamDirectory {

    fun teamIds(): List<Long>
}
