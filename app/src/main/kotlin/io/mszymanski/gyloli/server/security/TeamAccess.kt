package io.mszymanski.gyloli.server.security

import io.mszymanski.gyloli.server.team.Team
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service

/**
 * Decides which teams the caller may see. A team names the directory group whose
 * members may see it; membership arrives as an authority derived from the group's
 * common name by the LDAP authorities populator, so `cn=backend,ou=teams,...`
 * corresponds to `ROLE_BACKEND`. Only the administrator role is configuration.
 */
@Service
class TeamAccess(
    private val properties: SecurityProperties,
) {

    fun roles(): Set<String> {
        val authentication = SecurityContextHolder.getContext().authentication
        if (authentication == null || !authentication.isAuthenticated || authentication is AnonymousAuthenticationToken) {
            return emptySet()
        }
        return authentication.authorities.mapNotNull(GrantedAuthority::getAuthority).toSet()
    }

    fun isAdmin(): Boolean = properties.adminRole in roles()


    /** The authority that membership of this group grants. */
    fun authorityFor(ldapGroup: String): String = ROLE_PREFIX + commonName(ldapGroup).uppercase()

    fun canSee(team: Team): Boolean {
        if (isAdmin()) return true
        val group = team.ldapGroup?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        return authorityFor(group) in roles()
    }

    fun requireVisible(team: Team) {
        if (!canSee(team)) throw TeamForbiddenException(team.name)
    }

    fun requireAdmin() {
        if (!isAdmin()) throw AdminRequiredException()
    }

    /** "cn=backend,ou=teams,dc=gyloli,dc=io" -> "backend"; a bare name is left alone. */
    private fun commonName(ldapGroup: String): String {
        val relative = ldapGroup.split(',').firstOrNull()?.trim().orEmpty()
        val name = if (relative.startsWith("cn=", ignoreCase = true)) relative.substring(3) else relative
        return name.trim().replace(NON_ROLE_CHARACTERS, "_")
    }

    private companion object {
        const val ROLE_PREFIX = "ROLE_"
        val NON_ROLE_CHARACTERS = Regex("[^A-Za-z0-9]+")
    }
}

class TeamForbiddenException(name: String) : RuntimeException("You do not have access to team \"$name\"")

class AdminRequiredException : RuntimeException("This action requires the organization administrator role")
