package io.mszymanski.orknux.server.security

import org.springframework.boot.web.server.autoconfigure.ServerProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.session.config.SessionRepositoryCustomizer
import org.springframework.session.jdbc.JdbcIndexedSessionRepository
import org.springframework.session.jdbc.config.annotation.web.http.EnableJdbcHttpSession

/**
 * Sessions in the database, so signing in outlives the process.
 *
 * Enabled here rather than left to auto-configuration, which is not a preference:
 * Spring Boot 4 splits auto-configuration into a module per technology —
 * `spring-boot-jdbc`, `spring-boot-security`, `spring-boot-ldap` and so on — and
 * there is no session module on this classpath. Adding `spring-session-jdbc` alone
 * put the repository *class* within reach and wired nothing: no repository bean, no
 * filter, and every session still a Tomcat object in this JVM's heap. It looked
 * exactly like it was working — the tables existed, nothing errored, and the table
 * stayed empty while somebody was signed in.
 *
 * The annotation creates the repository and the filter itself, so this does not
 * depend on which auto-configuration modules happen to be present.
 *
 * The schema is not created here. Flyway owns it, in V71, copied from Spring
 * Session's own DDL — two things creating tables is two things that can disagree.
 */
@Configuration(proxyBeanMethods = false)
/*
 * The cleanup schedule is written here rather than taken from a property: Spring
 * Session validates this attribute as a cron expression before any placeholder in it
 * is resolved, so `${...}` fails startup with "cleanupCron must be valid" — which
 * says nothing about the actual cause. Half past every hour, and the hour, is not
 * something an installation needs to tune.
 */
@EnableJdbcHttpSession(cleanupCron = "0 */30 * * * *")
class SessionStoreConfig {

    /**
     * How long a session lasts, from the one place that already says so.
     *
     * `server.servlet.session.timeout` is where anybody would look for it, and it is
     * what the servlet container uses. Without this the repository would quietly keep
     * its own default of thirty minutes, and the setting in the configuration file
     * would be a documented lie.
     */
    @Bean
    fun sessionTimeout(server: ServerProperties): SessionRepositoryCustomizer<JdbcIndexedSessionRepository> =
        SessionRepositoryCustomizer { repository ->
            server.servlet.session.timeout?.let { repository.setDefaultMaxInactiveInterval(it) }
        }
}
