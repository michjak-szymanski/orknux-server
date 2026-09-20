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
     * log rather than sending a link to nowhere. That is the rule for a mail: a
     * link nobody can follow is worse in somebody's inbox than no link at all.
     *
     * A picture is the exception, and deliberately. `StepPictures` and
     * `TaskPictures` write an address into markdown a *model* is handed, and a
     * model pastes what it was given - so a path with no host behind it arrives
     * in Slack as the construction that would have been a link. They fall back
     * to the development address rather than write one, because a wrong link is
     * at least a link, and the alternative is a paragraph of angle brackets.
     */
    val baseUrl: String = "http://localhost:5173",
)
