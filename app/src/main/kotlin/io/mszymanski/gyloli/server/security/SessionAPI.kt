package io.mszymanski.gyloli.server.security

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
) {

    private val securityContextRepository = HttpSessionSecurityContextRepository()

    @PostMapping
    fun login(
        @RequestBody credentials: LoginRequest,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): SessionUser {
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

    private fun sessionUser(authentication: Authentication): SessionUser {
        val user = SessionUser(authentication)
        return user.copy(admin = properties.adminRole in user.roles)
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
