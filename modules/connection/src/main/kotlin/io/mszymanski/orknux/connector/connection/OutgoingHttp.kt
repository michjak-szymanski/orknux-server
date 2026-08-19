package io.mszymanski.orknux.connector.connection

import io.mszymanski.orknux.connector.proxy.ProxyRouter
import org.springframework.stereotype.Service
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** What came back, or why nothing did. */
sealed interface HttpAnswer {

    /** The service answered. Whether that answer is good news is the caller's to judge. */
    data class Answered(val status: Int, val body: String, val contentType: String?) : HttpAnswer

    /**
     * The call was not made, and would not be worth making again.
     *
     * A URL that is not a URL, a scheme this will not speak, a host that resolves
     * somewhere it must not go. Trying again changes nothing; something has to be
     * edited first.
     */
    data class Refused(val reason: String) : HttpAnswer

    /**
     * The call was made and nothing came back: refused connection, timeout, DNS
     * that failed at the last moment. Worth trying again, which is the whole
     * difference from [Refused].
     */
    data class Unreachable(val reason: String) : HttpAnswer
}

/**
 * Calls something outside, for a workflow that asked to.
 *
 * Separate from [ConnectionProbe] because the questions differ — a probe asks "is
 * anything there", this asks whatever the workflow said — but the rule about what
 * may be called is shared, not copied: [ConnectionProbe.vet] is asked, so there is
 * one answer to "is this URL safe to call" and one place to change it.
 *
 * Redirects are not followed, for the same reason the probe does not follow them: a
 * redirect can leave the host somebody configured and take the headers with it,
 * which is how a request meant for an internal service ends up somewhere else. A
 * 301 is returned as the answer it is, for the workflow to deal with.
 */
@Service
class OutgoingHttp(
    private val properties: ConnectionProperties,
    private val probe: ConnectionProbe,
    private val proxies: ProxyRouter,
) {

    private val client: HttpClient = proxies.builder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(properties.requestTimeoutSeconds))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    fun call(url: String, method: String, headers: Map<String, String>, body: String?): HttpAnswer {
        probe.vet(url)?.let { return HttpAnswer.Refused(it) }

        val verb = method.trim().uppercase().ifEmpty { "GET" }
        if (verb !in METHODS) return HttpAnswer.Refused("$verb is not a method this can send")

        val builder = try {
            HttpRequest.newBuilder(URI(url))
        } catch (_: Exception) {
            return HttpAnswer.Refused("The URL is not valid")
        }

        builder.timeout(Duration.ofSeconds(properties.requestTimeoutSeconds))

        /*
         * The caller's headers, minus the ones the client owns. Setting Host or
         * Content-Length by hand is either rejected outright or quietly wrong, and a
         * workflow that tried would fail with an exception about a restricted header
         * rather than anything to do with what it was trying to say.
         */
        headers.forEach { (name, value) ->
            if (name.trim().lowercase() !in CLIENT_OWNED) {
                runCatching { builder.header(name.trim(), value) }
            }
        }

        val content = body.orEmpty()
        val publisher = if (verb in WITHOUT_BODY || content.isEmpty()) {
            HttpRequest.BodyPublishers.noBody()
        } else {
            HttpRequest.BodyPublishers.ofString(content)
        }

        return try {
            val response = client.send(builder.method(verb, publisher).build(), HttpResponse.BodyHandlers.ofString())
            HttpAnswer.Answered(
                status = response.statusCode(),
                body = response.body().orEmpty().take(MAX_BODY),
                contentType = response.headers().firstValue("content-type").orElse(null),
            )
        } catch (failure: Exception) {
            HttpAnswer.Unreachable(failure.message ?: "The service could not be reached")
        }
    }

    private companion object {
        val METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")

        /** Sending a body with these is either meaningless or refused by the client. */
        val WITHOUT_BODY = setOf("GET", "HEAD")

        /** The client sets these itself, and rejects an attempt to set them again. */
        val CLIENT_OWNED = setOf("host", "content-length", "connection", "upgrade", "expect")

        /**
         * How much of an answer is kept.
         *
         * What comes back is carried through the rest of the run and written to the
         * execution's history, so an endpoint that returns a hundred megabytes would
         * put a hundred megabytes in the database. A megabyte is more than anything a
         * workflow reads a field off, and the truncation is visible in the result.
         */
        const val MAX_BODY = 1_000_000
    }
}
