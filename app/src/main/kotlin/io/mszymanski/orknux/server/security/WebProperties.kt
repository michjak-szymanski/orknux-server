package io.mszymanski.orknux.server.security

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "orknux.web")
data class WebProperties(
    /** Origins allowed to call the API with session cookies, e.g. the Vite dev server. */
    val allowedOrigins: List<String> = listOf("http://localhost:5173"),

    /**
     * Where this installation is reached from, as somebody's browser spells it.
     *
     * Needed the moment the server writes a link into something that leaves it -
     * a password reset mail is the first, and every later notification will want
     * the same. It has to be configured because there is nowhere honest to work
     * it out from: the `Host` header is written by whoever is calling, so a link
     * built from it is a link an attacker chooses the address of, and the one
     * being posted here contains a secret that opens an account.
     *
     * The development default is the Vite server, matching [allowedOrigins].
     * Empty means no link can be written, and whatever wanted one says so in the
     * log rather than sending a link to nowhere.
     */
    val baseUrl: String = "http://localhost:5173",
)
