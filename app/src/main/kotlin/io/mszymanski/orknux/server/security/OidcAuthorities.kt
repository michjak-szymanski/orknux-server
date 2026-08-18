package io.mszymanski.orknux.server.security

import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.core.ClaimAccessor
import org.springframework.stereotype.Component

/**
 * What an OIDC token says about somebody, in the same shape LDAP produces.
 *
 * The rest of the application asks [RoleResolver] which roles a set of authorities
 * grants, and does not care where the authorities came from. LDAP's populator
 * produces `ROLE_BACKEND` from a group's common name; this produces the claim's
 * values as they are, so a mapping written for `platform-admins` matches what the
 * provider actually sends.
 *
 * Both spellings are emitted for each value — the raw one and the `ROLE_`-prefixed
 * uppercase one. That is not hedging: it means an installation can move from LDAP to
 * OIDC without rewriting anything, because a role called `Backend` is still matched
 * by name, while a mapping written against the provider's exact claim value also
 * works. Neither spelling grants anything a role does not already say.
 */
@Component
class OidcAuthorities(private val properties: SecurityProperties) {

    /** The authorities this token carries, from the configured claim. */
    fun from(claims: ClaimAccessor): Set<GrantedAuthority> =
        valuesOf(claims, properties.oidc.rolesClaim)
            .flatMap { value -> listOf(value, prefixed(value)) }
            .map { SimpleGrantedAuthority(it) }
            .toSet()

    /** What to call this person on screen, falling back to the subject. */
    fun usernameOf(claims: ClaimAccessor): String {
        val preferred = claims.getClaimAsString(properties.oidc.usernameClaim)
        if (!preferred.isNullOrBlank()) return preferred
        return claims.getClaimAsString("sub") ?: "unknown"
    }

    /**
     * The claim's values, whatever shape it arrived in.
     *
     * A list of strings is the common case, a single string happens, and Entra sends
     * a list of objects when groups are emitted as claims with extra fields. A
     * provider that answers in a shape this does not understand grants nothing, which
     * is the safe direction: no roles rather than roles nobody meant.
     */
    private fun valuesOf(claims: ClaimAccessor, name: String): List<String> {
        return when (val claim = claims.getClaim<Any?>(name)) {
            null -> emptyList()
            is String -> claim.split(SEPARATORS).map { it.trim() }.filter { it.isNotEmpty() }
            is Collection<*> -> claim.mapNotNull { entry ->
                when (entry) {
                    is String -> entry.trim().ifEmpty { null }
                    is Map<*, *> -> (entry["value"] ?: entry["name"] ?: entry["id"])?.toString()?.trim()
                    else -> null
                }
            }

            else -> emptyList()
        }
    }

    /** "platform-admins" -> "ROLE_PLATFORM_ADMINS", the way the LDAP populator writes one. */
    private fun prefixed(value: String): String =
        "ROLE_" + value.trim().replace(NON_ROLE_CHARACTERS, "_").uppercase()

    private companion object {
        /** A single-string claim may hold several, space or comma separated. */
        val SEPARATORS = Regex("[,\\s]+")
        val NON_ROLE_CHARACTERS = Regex("[^A-Za-z0-9]+")
    }
}
