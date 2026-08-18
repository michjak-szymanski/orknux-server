package io.mszymanski.orknux.server.security

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.AuthenticationException
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.ldap.userdetails.InetOrgPerson
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import io.mszymanski.orknux.server.user.InternalAuthentication
import org.springframework.security.authentication.AuthenticationManager

/**
 * Username/password sign-in against the directory. The credentials are checked by
 * the LDAP [AuthenticationManager]; the resulting authentication is stored in the
 * HTTP session, so subsequent GraphQL calls are attributed to that LDAP user.
 */
@RestController
@RequestMapping(LOGIN_PATH)
class SessionAPI(
    private val authenticationManager: AuthenticationManager,
    private val properties: SecurityProperties,
    private val resolver: RoleResolver,
    private val internal: InternalAuthentication,
) {

    private val securityContextRepository = HttpSessionSecurityContextRepository()

    @PostMapping
    fun login(
        @RequestBody credentials: LoginRequest,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): SessionUser {
        /*
         * Somebody this installation made up, checked first.
         *
         * Before the directory, and whatever the configured method is: an
         * internal user exists precisely because the provider does not know
         * them, so an installation signing in with OIDC still has to let them
         * in. Everybody else falls through to the door below.
         */
        internal.authenticate(credentials.username, credentials.password)?.let { authenticated ->
            request.getSession(false)?.invalidate()
            val context = SecurityContextHolder.createEmptyContext().apply { this.authentication = authenticated }
            SecurityContextHolder.setContext(context)
            securityContextRepository.saveContext(context, request, response)
            return sessionUser(authenticated)
        }

        /*
         * There is no password to check where the provider holds them. Refused
         * rather than quietly failing against a directory this installation does not
         * use, because the answer is not "wrong password" — it is "not this way".
         */
        if (properties.authMethod != AuthMethod.LDAP) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "This installation signs in with ${properties.oidc.displayName}, not with a password.",
            )
        }

        val authentication = try {
            authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken(credentials.username, credentials.password),
            )
        } catch (_: AuthenticationException) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password")
        }

        // Drop any pre-login session so the authenticated user gets a fresh id.
        request.getSession(false)?.invalidate()

        val context = SecurityContextHolder.createEmptyContext().apply { this.authentication = authentication }
        SecurityContextHolder.setContext(context)
        securityContextRepository.saveContext(context, request, response)

        return sessionUser(authentication)
    }

    @GetMapping
    fun current(authentication: Authentication): SessionUser = sessionUser(authentication)

    /**
     * Who this is, and whether they administer.
     *
     * Asked of the resolver rather than by looking for one configured authority, so
     * that a role carrying the administrator scope counts — which is the whole point
     * of roles having scopes. The configured authority still counts on its own,
     * inside the resolver, so nothing that worked before stops working.
     */
    private fun sessionUser(authentication: Authentication): SessionUser {
        val user = SessionUser(authentication)
        return user.copy(admin = resolver.administers(user.roles.toSet()))
    }

    @DeleteMapping
    fun logout(request: HttpServletRequest) {
        request.getSession(false)?.invalidate()
        SecurityContextHolder.clearContext()
    }
}

/**
 * Spring Boot 4 ships Jackson 3, which has no Kotlin module on the classpath,
 * so the creator is bound explicitly rather than from constructor parameter names.
 */
data class LoginRequest @JsonCreator constructor(
    @JsonProperty("username") val username: String,
    @JsonProperty("password") val password: String,
)

data class SessionUser @JsonCreator constructor(
    @JsonProperty("username") val username: String,
    @JsonProperty("roles") val roles: List<String>,
    /** Whether the caller holds the configured admin role. */
    @JsonProperty("admin") val admin: Boolean = false,
    /** The directory's mail attribute, shown in the user menu; absent when unset. */
    @JsonProperty("email") val email: String? = null,
) {
    constructor(authentication: Authentication) : this(
        username = authentication.name,
        roles = authentication.authorities
            .mapNotNull(GrantedAuthority::getAuthority)
            .filter { it.startsWith("ROLE_") },
        email = (authentication.principal as? InetOrgPerson)?.mail?.takeIf(String::isNotBlank),
    )
}
