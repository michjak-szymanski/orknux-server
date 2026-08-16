package io.mszymanski.orknux.server.security

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "orknux.security")
data class SecurityProperties(
    /** Holders see the Admin section and every workspace, whatever their other roles. */
    val adminRole: String = "ROLE_ADMINS",
)
