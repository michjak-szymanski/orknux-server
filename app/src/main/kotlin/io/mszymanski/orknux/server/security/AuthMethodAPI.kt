package io.mszymanski.orknux.server.security

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * How to sign in to this installation, asked before anybody has.
 *
 * The sign-in screen cannot draw itself without this. Under LDAP it needs a username
 * and password box; under OIDC there is nothing to type — only a button pointing at
 * the provider, named the way the people signing in know it.
 *
 * Open, and deliberately says nothing else. Which provider an installation uses is
 * visible to anyone who reaches the sign-in page anyway, since that is where they are
 * about to be sent.
 */
@RestController
@RequestMapping(AUTH_METHOD_PATH)
class AuthMethodAPI(private val properties: SecurityProperties) {

    @GetMapping
    fun method(): AuthMethodView = AuthMethodView(
        method = properties.authMethod.name,
        displayName = properties.oidc.displayName,
        // Where the browser flow starts. Spring registers this path for the
        // registration id; naming it here keeps the interface from having to know
        // Spring's URL conventions.
        authorizeUrl = if (properties.authMethod == AuthMethod.OIDC) OIDC_AUTHORIZE_PATH else null,
    )
}

data class AuthMethodView @JsonCreator constructor(
    @JsonProperty("method") val method: String,
    /** What the button says, where there is a button. */
    @JsonProperty("displayName") val displayName: String,
    /** Where to send the browser, or null when there is a password box instead. */
    @JsonProperty("authorizeUrl") val authorizeUrl: String?,
)

/** Spring's authorization endpoint for the registration this application configures. */
const val OIDC_REGISTRATION_ID = "orknux"

const val OIDC_AUTHORIZE_PATH = "/oauth2/authorization/$OIDC_REGISTRATION_ID"
