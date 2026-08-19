package io.mszymanski.orknux.server.security

import jakarta.servlet.DispatcherType
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import io.mszymanski.orknux.server.user.TokenAuthenticationFilter
import org.springframework.security.crypto.factory.PasswordEncoderFactories
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.security.web.authentication.HttpStatusEntryPoint
import org.springframework.security.web.util.matcher.DispatcherTypeRequestMatcher
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WebProperties::class, SecurityProperties::class)
class SecurityConfig {

    /**
     * How a password is stored, for the few users this installation keeps one for.
     *
     * Delegating, so the hash says which algorithm made it: the day bcrypt is
     * not the answer, what is already stored still verifies and what is written
     * next is stronger, with nothing to migrate by hand.
     */
    @Bean
    fun passwordEncoder(): PasswordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder()

    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        properties: SecurityProperties,
        tokens: TokenAuthenticationFilter,
    ): SecurityFilterChain {
        http {
            cors { }
            // The API is cookie-session based; a CSRF token flow still needs adding
            // before this is exposed anywhere but a dev machine.
            csrf { disable() }
            httpBasic { disable() }
            formLogin { disable() }
            logout { disable() }
            authorizeHttpRequests {
                /*
                 * The error page, which is a second dispatch of a request that
                 * already passed this chain.
                 *
                 * Without this, anything thrown by a REST endpoint is forwarded
                 * to `/error`, that forward is treated as a fresh unauthenticated
                 * request, and the caller is answered 401 with an empty body. So
                 * asking for a workspace that does not exist said "sign in", a
                 * failed upload said "sign in", and every real fault arrived
                 * wearing the one costume guaranteed to send somebody looking in
                 * the wrong place. GraphQL was unaffected, which is why it went
                 * unnoticed: it answers its own errors with 200.
                 *
                 * Permitting the dispatch does not expose anything — the request
                 * that produced the error was authorised on its way in, and the
                 * error page carries a status and a message, not data.
                 */
                authorize(DispatcherTypeRequestMatcher(DispatcherType.ERROR), permitAll)
                /*
                 * The async dispatch, which is the back half of a request that
                 * answered with a promise rather than a value.
                 *
                 * The same argument as the error dispatch above, and for the
                 * same reason it has to be said: the container comes back to
                 * finish writing the response and that arrives here looking
                 * like a fresh request, with nobody on it - the filters that
                 * establish who is calling all decline to run twice, by
                 * design. Refusing it answers 401 to a caller who was
                 * authorised on the way in and whose answer is already
                 * computed.
                 *
                 * Nothing is opened by this. A request only reaches an async
                 * dispatch by having passed the check above on its first, and
                 * what happens here is serialising an answer that was decided
                 * while somebody was still known to be there. `orknux_news` is
                 * what made it necessary; see [McpAPI].
                 */
                authorize(DispatcherTypeRequestMatcher(DispatcherType.ASYNC), permitAll)
                authorize(HttpMethod.POST, LOGIN_PATH, permitAll)
                /*
                 * How to sign in is not itself a secret, and the sign-in screen has
                 * to ask before anybody can: with OIDC there is no password box to
                 * draw, only a button pointing at the provider.
                 */
                authorize(HttpMethod.GET, AUTH_METHOD_PATH, permitAll)
                /*
                 * A webhook is called by whatever is out there — a build server,
                 * a form, another product — and none of them can sign in here.
                 * What answers is a path nothing else knows and a shape it has
                 * to match; anything else is a 404. Proving who the caller is
                 * comes later, and will be the trigger's own business.
                 */
                authorize(HttpMethod.POST, "$WEBHOOK_PATH/**", permitAll)
                authorize(anyRequest, authenticated)
            }
            // Answer unauthenticated calls with 401 instead of redirecting to a login page.
            exceptionHandling {
                authenticationEntryPoint = HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)
            }
        }

        /*
         * Both OIDC flows, together, and only where OIDC is what this installation
         * uses.
         *
         * The browser flow ends in the same session cookie password sign-in issues,
         * so everything past the front door — the GraphQL API, the audit log, the
         * workspace checks — is unchanged and does not know which one happened. The
         * bearer flow is for callers that hold a token already and have nowhere to
         * keep a cookie; it validates per request and starts no session.
         *
         * PKCE is not configured here because Spring Security sends it by default
         * for public clients and for confidential ones where the provider advertises
         * it. Turning it on explicitly would be describing what already happens.
         */
        if (properties.authMethod == AuthMethod.OIDC) {
            http {
                oauth2Login { }
                oauth2ResourceServer { jwt { } }
            }
        }

        /*
         * A token is read before anything asks who this is.
         *
         * Ahead of the session filter, so a caller carrying one is somebody by
         * the time authorisation runs - and behind nothing that matters, since
         * a request without the header passes straight through. This is what
         * makes the API and the MCP endpoint reachable by something with no
         * browser to keep a cookie in.
         */
        http.addFilterBefore(tokens, UsernamePasswordAuthenticationFilter::class.java)

        return http.build()
    }

    @Bean
    fun corsConfigurationSource(properties: WebProperties): CorsConfigurationSource {
        val configuration = CorsConfiguration().apply {
            allowedOrigins = properties.allowedOrigins
            allowedMethods = listOf("GET", "POST", "DELETE", "OPTIONS")
            allowedHeaders = listOf("Content-Type")
            allowCredentials = true
        }
        return UrlBasedCorsConfigurationSource().apply {
            registerCorsConfiguration("/**", configuration)
        }
    }
}

const val LOGIN_PATH = "/api/session"

/** Open: what the sign-in screen has to know before anybody has signed in. */
const val AUTH_METHOD_PATH = "/api/auth/method"

/** Where a webhook trigger answers; open, because its callers cannot sign in. */
const val WEBHOOK_PATH = "/api/webhooks"
