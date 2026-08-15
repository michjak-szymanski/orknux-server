package io.mszymanski.gyloli.server.ldap

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "gyloli.ldap")
data class LdapProperties(
    val userSearchBase: String = "ou=people",
    val userSearchFilter: String = "(uid={0})",
    val groupSearchBase: String = "ou=groups",
    val groupSearchFilter: String = "(member={0})",
)
