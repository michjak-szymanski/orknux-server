package io.mszymanski.orknux.server.user

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import io.mszymanski.orknux.server.security.PASSWORD_RESET_PATH
import io.mszymanski.orknux.server.security.TooManySignInAttempts
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Duration

/**
 * The forgotten-password door, open to anybody.
 *
 * It has to be: the whole point is that the person on the other side cannot sign
 * in. So it is next to `POST /api/session` in every way that matters - permitted
 * in [io.mszymanski.orknux.server.security.SecurityConfig], counting how often
 * anybody knocks, and taking the caller's real address rather than a header they
 * wrote themselves.
 *
 * REST rather than GraphQL, for the same reason sign-in is: the GraphQL endpoint
 * is authenticated as a whole, and carving two unauthenticated fields out of it
 * would mean the one schema no longer has one answer to who may call it.
 */
@RestController
@RequestMapping(PASSWORD_RESET_PATH)
class PasswordResetAPI(private val resets: PasswordResetService) {

    /**
     * Asks for a link, and is told the same thing either way.
     *
     * One sentence, one status, whether that address belongs to an account, to a
     * directory user whose password is not this installation's to change, or to
     * nobody at all. The wording says "if" on purpose: it is honest about what it
     * does not promise, rather than claiming a mail was sent that was not.
     */
    @PostMapping
    fun request(@RequestBody asked: ResetRequest, request: HttpServletRequest): ResetAnswer {
        val from = request.remoteAddr ?: "unknown"
        try {
            resets.request(asked.email, from)
        } catch (busy: TooManySignInAttempts) {
            // The throttle is sign-in's class and says so; this caller was not
            // signing in, and being told they were would send them looking at the
            // wrong form.
            throw tooOften(busy)
        }
        return ResetAnswer(SAME_ANSWER)
    }

    /**
     * Follows the link and sets the password.
     *
     * This one does say what went wrong, which is not a change of mind about
     * disclosure: the caller is holding a token, and a token is either one this
     * installation issued or it is not. Refusing silently would leave somebody
     * with a link that had simply expired retyping a password at a form that
     * never says why.
     */
    @PostMapping("/complete")
    fun complete(@RequestBody chosen: ResetCompletion, request: HttpServletRequest): CompletedReset {
        val from = request.remoteAddr ?: "unknown"
        return try {
            CompletedReset(resets.complete(chosen.token, chosen.password, from))
        } catch (busy: TooManySignInAttempts) {
            throw tooOften(busy)
        }
    }

    /** A link that is not one, or not any more. */
    @ExceptionHandler(PasswordResetInvalidException::class)
    fun invalid(failure: PasswordResetInvalidException) =
        ResponseStatusException(HttpStatus.BAD_REQUEST, failure.message)

    @ExceptionHandler(PasswordTooShortException::class)
    fun tooShort(failure: PasswordTooShortException) =
        ResponseStatusException(HttpStatus.BAD_REQUEST, failure.message)

    /**
     * The same 429 the throttle raised, in words that fit the form it came from.
     *
     * The wait is read back off the header rather than passed alongside, so how
     * long anybody is asked to wait is still decided in one place.
     */
    private fun tooOften(busy: TooManySignInAttempts) =
        TooManyResetRequests(Duration.ofSeconds(busy.headers.getFirst(HttpHeaders.RETRY_AFTER)?.toLongOrNull() ?: 1))

    private companion object {
        /**
         * What everybody is told, whoever they asked about.
         *
         * A constant rather than a sentence written at each return, because the
         * moment there are two of them somebody will make one of them more
         * helpful and that will be the disclosure.
         */
        const val SAME_ANSWER = "If that address belongs to an account, a link is on its way."
    }
}

/**
 * Knocking on the forgotten-password door too often: 429, and how long to leave it.
 *
 * The counting is [io.mszymanski.orknux.server.security.SignInThrottle]'s, which
 * is deliberate - one set of rules about backing off, not two - but its sentence
 * names sign-in, and a person who has just asked for a reset link would read that
 * as being locked out of a form they were not using.
 */
class TooManyResetRequests(private val wait: Duration) : ResponseStatusException(
    HttpStatus.TOO_MANY_REQUESTS,
    "Too many password reset requests. Try again in ${wait.toSeconds().coerceAtLeast(1)} seconds.",
) {

    override fun getHeaders(): HttpHeaders = HttpHeaders().apply {
        set(HttpHeaders.RETRY_AFTER, wait.toSeconds().coerceAtLeast(1).toString())
    }
}

/**
 * Spring Boot 4 ships Jackson 3, which has no Kotlin module on the classpath, so
 * the creator is bound explicitly rather than from constructor parameter names -
 * the same reason [io.mszymanski.orknux.server.security.LoginRequest] does it.
 */
data class ResetRequest @JsonCreator constructor(
    @JsonProperty("email") val email: String,
)

data class ResetAnswer @JsonCreator constructor(
    @JsonProperty("message") val message: String,
)

data class ResetCompletion @JsonCreator constructor(
    @JsonProperty("token") val token: String,
    @JsonProperty("password") val password: String,
)

/** Who the link belonged to, so the sign-in form can be filled in for them. */
data class CompletedReset @JsonCreator constructor(
    @JsonProperty("username") val username: String,
)
