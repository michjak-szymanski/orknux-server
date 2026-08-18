package io.mszymanski.orknux.server.user

import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.security.authentication.event.AuthenticationSuccessEvent
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Writes down who signs in.
 *
 * The provider knows its people; this installation only meets them one at a
 * time, at the door. Each successful sign-in upserts an EXTERNAL row, which is
 * how the Users page has anything external to list at all — there is no
 * directory query behind it, deliberately: an LDAP tree or an OIDC issuer is
 * not ours to enumerate, and the people who matter here are the ones who have
 * actually been here.
 *
 * One listener for both doors. Spring publishes this event for the password
 * path and the OIDC path alike, so neither flow needs its own hook.
 */
@Component
class UserDetection(private val users: AppUserRepository) {

    @EventListener
    @Transactional
    fun onSignIn(event: AuthenticationSuccessEvent) {
        val authentication = event.authentication
        val username = authentication.name?.takeIf { it.isNotBlank() } ?: return

        /*
         * The OIDC principal often carries a human name; LDAP's rarely does.
         * Taken when offered, kept when not: a display name somebody saw once
         * should not turn back into a login the next time they arrive.
         */
        val principal = authentication.principal
        val called = when (principal) {
            is OidcUser -> principal.fullName?.takeIf { it.isNotBlank() }
            is UserDetails -> null
            else -> null
        }

        val held = users.findByUsername(username)
        if (held == null) {
            users.save(
                AppUser(
                    username = username,
                    displayName = called ?: username,
                    type = UserType.EXTERNAL,
                ),
            )
            log.info("First sign-in recorded for {}", username)
        } else if (called != null && held.displayName != called) {
            held.displayName = called
            users.save(held)
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(UserDetection::class.java)
    }
}
