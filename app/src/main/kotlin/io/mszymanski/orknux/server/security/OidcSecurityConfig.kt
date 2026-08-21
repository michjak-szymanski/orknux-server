package io.mszymanski.orknux.server.security

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.convert.converter.Converter
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.mapping.GrantedAuthoritiesMapper
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority
import org.springframework.security.oauth2.core.user.OAuth2UserAuthority
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.oauth2.client.registration.ClientRegistrations
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtClaimNames
import org.springframework.security.oauth2.jwt.JwtClaimValidator
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtIssuerValidator
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.util.UriComponentsBuilder
import java.net.URI

/**
 * What an OIDC identity is worth here, for both ways one can arrive.
 *
 * The browser flow and the bearer flow reach this application through different
 * Spring Security machinery — one ends in an `OidcUser` built from the ID token and
 * the userinfo endpoint, the other in a `Jwt` validated per request — but they are
 * the same provider saying the same thing. Both are converted with [OidcAuthorities],
 * so a claim grants the same roles whichever door it came through. Anything else and
 * an account would have different permissions depending on whether a person or a
 * script was using it.
 *
 * Only present when this installation signs in with OIDC. Under LDAP the beans are
 * absent rather than inert, so a half-written configuration cannot half-enable it.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = ["orknux.security.auth-method"], havingValue = "OIDC")
class OidcSecurityConfig {

    /**
     * The provider, discovered from its issuer.
     *
     * Built here rather than left to Spring's own configuration properties for one
     * practical reason: those are read whether or not OIDC is in use, and a
     * registration with an empty client id fails the whole application at startup.
     * An installation running LDAP should not have to write half an OIDC
     * configuration to keep the server from refusing to boot.
     *
     * The call to the provider happens once, here, at startup — so a wrong issuer is
     * a clear failure on the way up rather than a puzzle at the first sign-in.
     */
    @Bean
    fun clientRegistrationRepository(
        properties: SecurityProperties,
        transport: OidcTransport,
    ): ClientRegistrationRepository {
        val oidc = properties.oidc
        require(oidc.issuer.isNotBlank()) {
            "orknux.security.oidc.issuer is not set, and this installation is configured to sign in " +
                "with OIDC. Set the issuer, or set orknux.security.auth-method to LDAP."
        }
        require(oidc.clientId.isNotBlank()) {
            "orknux.security.oidc.client-id is not set, and this installation is configured to sign " +
                "in with OIDC."
        }

        val registration = discover(oidc.issuer, transport)
            .registrationId(OIDC_REGISTRATION_ID)
            .clientId(oidc.clientId)
            .clientName(oidc.displayName)
            .scope(oidc.scopes)
            .apply { if (oidc.clientSecret.isNotBlank()) clientSecret(oidc.clientSecret) }
            .build()

        return InMemoryClientRegistrationRepository(registration)
    }

    /**
     * How a bearer token is validated: against the same issuer the browser flow uses,
     * and against this application's own name.
     *
     * Same provider, same keys, same claims - a token and a session are the same
     * statement about somebody, and validating them differently would eventually mean
     * disagreeing about who they are.
     *
     * The issuer alone is not enough, which is what this used to check and nothing more.
     * A realm or a tenant serves several applications, and every token it mints carries
     * this issuer and a signature from these keys - so a token issued to some other
     * application registered beside this one would have been accepted here as an identity.
     * Roles come from a claim, so a group named `admins` over there would have administered
     * here. The `aud` claim is where the provider wrote down who the token was for, so
     * that is what is read.
     */
    @Bean
    fun jwtDecoder(properties: SecurityProperties, transport: OidcTransport): JwtDecoder {
        val issuer = properties.oidc.issuer
        // Discovery and the key set, both over the routed client: a decoder that
        // cannot fetch the keys rejects every token that arrives, which reads on
        // screen as an authentication failure and is nothing of the kind.
        val decoder = NimbusJwtDecoder.withIssuerLocation(issuer)
            .restOperations(transport.restOperations())
            .build()
        decoder.setJwtValidator(
            JwtValidators.createDefaultWithValidators(
                JwtIssuerValidator(issuer),
                audienceValidator(properties.oidc),
            ),
        )
        return decoder
    }

    /**
     * A token is meant for this application if `aud` names it.
     *
     * Written against the claim rather than with Spring's own `JwtAudienceValidator`
     * because that one holds a single name, and two of them side by side would demand a
     * token carry both at once. An installation that lists more than one audience means
     * any of them will do, not all of them.
     */
    private fun audienceValidator(oidc: OidcProperties): OAuth2TokenValidator<Jwt> {
        val accepted = oidc.audiences.ifEmpty { listOf(oidc.clientId) }.filter { it.isNotBlank() }
        require(accepted.isNotEmpty()) {
            "orknux.security.oidc.client-id is not set, and this installation is configured to sign " +
                "in with OIDC. It is what a bearer token has to name as its audience, unless " +
                "orknux.security.oidc.audiences says otherwise."
        }

        return JwtClaimValidator<Collection<String>>(JwtClaimNames.AUD) { audience ->
            audience != null && audience.any { it in accepted }
        }
    }

    /**
     * The browser flow's authorities.
     *
     * What Spring derived from the token — `SCOPE_openid` and the like — is kept:
     * it says what the token was issued for, and dropping it would make an OIDC
     * session look unlike every other session here. The claim-derived ones are added
     * beside it.
     */
    @Bean
    fun oidcAuthoritiesMapper(authorities: OidcAuthorities): GrantedAuthoritiesMapper =
        GrantedAuthoritiesMapper { granted ->
            val fromClaims = granted.flatMap { authority ->
                when (authority) {
                    is OidcUserAuthority ->
                        authorities.from(authority.idToken) +
                            (authority.userInfo?.let { authorities.from(it) } ?: emptySet())

                    is OAuth2UserAuthority -> authorities.from { authority.attributes }
                    else -> emptySet()
                }
            }
            (granted + fromClaims).toSet()
        }

    /**
     * The bearer flow's authorities, from the token itself.
     *
     * No userinfo call: a resource server validates what it was handed and answers.
     * A round trip to the provider on every request would make a script's call slower
     * than a person's, and would give the provider an outage budget over this server.
     */
    @Bean
    fun jwtAuthenticationConverter(
        authorities: OidcAuthorities,
    ): Converter<Jwt, AbstractAuthenticationToken> = Converter { jwt ->
        val granted: Collection<GrantedAuthority> = authorities.from(jwt)
        JwtAuthenticationToken(jwt, granted, authorities.usernameOf(jwt))
    }

    /**
     * The provider's own description of itself, fetched over the routed client.
     *
     * **Why this is not `ClientRegistrations.fromIssuerLocation`.** That method
     * builds its own `RestTemplate` and takes no argument that would let one be
     * supplied, so every discovery call it makes goes out over a plain
     * `HttpURLConnection` — direct, whatever the proxy rules say. On a network
     * that requires a proxy this failed while the application context was being
     * built, which is a failure with nowhere to report it: the server did not
     * start, so nobody could reach the page that configures the proxy.
     *
     * **What is kept.** The three well-known locations are asked for in the
     * order Spring Security asks for them, so a provider that publishes only
     * RFC 8414 metadata, or only OAuth authorization-server metadata, is found
     * exactly as it was before. What `fromOidcConfiguration` does not do, and
     * `fromIssuerLocation` did, is check that the document calls itself by the
     * issuer that was asked for - so that check is made below, because without
     * it a substituted document could point the flow at somebody else's
     * authorization and token endpoints.
     *
     * **What is different.** The failure message. Spring's said which locations
     * it tried; this says which locations were tried and what the last one did,
     * because on a proxied network the answer is nearly always in the second
     * half of that sentence.
     */
    private fun discover(issuer: String, transport: OidcTransport): ClientRegistration.Builder {
        val rest = transport.restOperations()
        val attempts = mutableListOf<String>()

        for (uri in wellKnownLocations(issuer)) {
            attempts += uri.toString()
            val configuration = try {
                rest.getForObject(uri, Map::class.java)
            } catch (failure: Exception) {
                attempts[attempts.lastIndex] += " (${failure.message})"
                continue
            } ?: continue

            /*
             * The document has to say it is the issuer that was asked for.
             *
             * `fromIssuerLocation` made this comparison and `fromOidcConfiguration`
             * cannot: it is handed a document with nothing to compare it against,
             * and takes the issuer it finds inside as the truth. Without the check
             * here, a document served in place of the provider's own - by anything
             * between here and there, the proxy included - would name whatever
             * authorization and token endpoints it liked, and this application
             * would send people to them.
             */
            val declared = configuration["issuer"]?.toString()
            if (declared != issuer) {
                attempts[attempts.lastIndex] += " (it calls itself $declared)"
                continue
            }

            @Suppress("UNCHECKED_CAST")
            return ClientRegistrations.fromOidcConfiguration(configuration as Map<String, Any>)
        }

        throw IllegalArgumentException(
            "The OIDC issuer $issuer could not be read. Tried: ${attempts.joinToString("; ")}",
        )
    }

    /**
     * Where a provider's metadata is published, in the order it is looked for.
     *
     * OIDC discovery appends its well-known path to the issuer; RFC 8414 inserts
     * it between the host and the issuer's own path, which is what a provider
     * serving several tenants from one host does. The third is the same
     * insertion for an OAuth authorization server that publishes no OIDC
     * document at all.
     */
    private fun wellKnownLocations(issuer: String): List<URI> {
        val components = UriComponentsBuilder.fromUriString(issuer).build()
        val path = components.path.orEmpty().trimEnd('/')
        val host = UriComponentsBuilder.fromUriString(issuer).replacePath(null).replaceQuery(null).build().toUriString()
        return listOf(
            URI.create("$host$path/.well-known/openid-configuration"),
            URI.create("$host/.well-known/openid-configuration$path"),
            URI.create("$host/.well-known/oauth-authorization-server$path"),
        )
    }

}
