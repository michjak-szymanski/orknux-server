package io.mszymanski.orknux.server.security

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.HttpStatusEntryPoint
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WebProperties::class, SecurityProperties::class)
class SecurityConfig {

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http {
            cors { }
            // The API is cookie-session based; a CSRF token flow still needs adding
            // before this is exposed anywhere but a dev machine.
            csrf { disable() }
            httpBasic { disable() }
            formLogin { disable() }
            logout { disable() }
            authorizeHttpRequests {
                authorize(HttpMethod.POST, LOGIN_PATH, permitAll)
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

/** Where a webhook trigger answers; open, because its callers cannot sign in. */
const val WEBHOOK_PATH = "/api/webhooks"
