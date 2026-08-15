package io.mszymanski.gyloli.server.security

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "gyloli.security")
data class SecurityProperties(
    /** Holders see the organization section and every team, whatever their other roles. */
    val adminRole: String = "ROLE_ADMINS",
)
