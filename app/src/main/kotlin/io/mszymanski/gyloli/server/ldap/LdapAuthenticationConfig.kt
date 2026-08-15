package io.mszymanski.gyloli.server.ldap

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.ldap.core.support.BaseLdapPathContextSource
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.config.ldap.LdapBindAuthenticationManagerFactory
import org.springframework.security.ldap.userdetails.DefaultLdapAuthoritiesPopulator
import org.springframework.security.ldap.userdetails.InetOrgPersonContextMapper
import org.springframework.security.ldap.userdetails.LdapAuthoritiesPopulator

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LdapProperties::class)
class LdapAuthenticationConfig {

    @Bean
    fun ldapAuthoritiesPopulator(
        contextSource: BaseLdapPathContextSource,
        properties: LdapProperties,
    ): LdapAuthoritiesPopulator =
        DefaultLdapAuthoritiesPopulator(contextSource, properties.groupSearchBase).apply {
            setGroupSearchFilter(properties.groupSearchFilter)
        }

    @Bean
    fun authenticationManager(
        contextSource: BaseLdapPathContextSource,
        authoritiesPopulator: LdapAuthoritiesPopulator,
        properties: LdapProperties,
    ): AuthenticationManager =
        LdapBindAuthenticationManagerFactory(contextSource).apply {
            setUserSearchBase(properties.userSearchBase)
            setUserSearchFilter(properties.userSearchFilter)
            setLdapAuthoritiesPopulator(authoritiesPopulator)
            // Keeps the inetOrgPerson attributes, mail among them, on the principal.
            setUserDetailsContextMapper(InetOrgPersonContextMapper())
        }.createAuthenticationManager()
}
