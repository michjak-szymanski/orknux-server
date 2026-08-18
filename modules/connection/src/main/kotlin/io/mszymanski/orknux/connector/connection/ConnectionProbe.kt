package io.mszymanski.orknux.connector.connection

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Service
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64

@ConfigurationProperties(prefix = "orknux.connection")
data class ConnectionProperties(
    /** How long a probe may take before it counts as a failure. */
    val probeTimeoutSeconds: Long = 5,
    /**
     * Link-local addresses reach cloud instance metadata, so a connection URL
     * resolving to one is refused rather than fetched. Private and loopback
     * addresses stay allowed: internal services are the point of the feature.
     */
    val allowLinkLocal: Boolean = false,

    /**
     * How long a workflow's own HTTP request may take.
     *
     * Longer than a probe's, which only asks whether anything is listening: a
     * request a workflow makes is doing work at the other end, and five seconds is
     * a short time to build a report in. Bounded all the same, because a step that
     * never returns holds the run that made it.
     */
    val requestTimeoutSeconds: Long = 30,

    /**
     * Where a service principal's token comes from.
     *
     * Configurable because the global cloud is not the only one — the US
     * government and China clouds each have their own authority — and because a
     * test cannot point at Microsoft.
     */
    val entraAuthority: String = "https://login.microsoftonline.com",
)

/** What the last probe found. */
enum class CheckOutcome {
    CONNECTED,
    FAILED,
}

data class CheckResult(val outcome: CheckOutcome, val message: String)

/**
 * Everything needed to talk to the other end, resolved in one place so the
 * stored credentials are read here and nowhere else. This is why the connector
 * exists: orknux-workflow asks for a target rather than for a secret, and the
 * providers built on top of it will make their calls through one.
 */
data class ConnectionTarget(
    val url: String,
    val authType: AuthType,
    val secret: String?,
    val headers: List<HttpHeader>,
) {

    /** The headers to send, credentials included. */
    fun requestHeaders(): List<Pair<String, String>> {
        val credential = when {
            secret.isNullOrBlank() -> null
            authType == AuthType.BEARER_TOKEN -> "Authorization" to "Bearer $secret"
            authType == AuthType.API_KEY -> "Authorization" to secret
            authType == AuthType.BASIC ->
                "Authorization" to "Basic " + Base64.getEncoder().encodeToString(secret.toByteArray())
            else -> null
        }
        return headers.map { it.name to it.value } + listOfNotNull(credential)
    }
}

fun WorkspaceConnection.target(): ConnectionTarget =
    ConnectionTarget(effectiveUrl, authType, secret, headers.toList())

fun McpServer.target(): ConnectionTarget = ConnectionTarget(address, authType, secret, headers.toList())

/**
 * Checks that a connection actually answers, so the workspace screen reports what was
 * observed rather than merely that credentials were typed in.
 */
@Service
class ConnectionProbe(
    private val properties: ConnectionProperties,
) {

    private val client: HttpClient = HttpClient.newBuilder()
        // Cleartext HTTP/2 negotiation hangs against servers that ignore the
        // upgrade, and a probe has no reason to care which version answers.
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(properties.probeTimeoutSeconds))
        // A redirect can leave the host the caller configured, taking the
        // credentials with it, so the first response is the answer.
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    /**
     * Whether a URL is one this service is willing to call at all, and why not
     * when it is not.
     *
     * Public because more than one kind of check needs the same answer, and
     * what is safe to call should be decided in one place — a second copy of
     * the link-local rule is a second copy to forget.
     */
    fun vet(url: String): String? {
        val uri = try {
            URI(url)
        } catch (_: Exception) {
            return "The URL is not valid"
        }
        if (uri.scheme?.lowercase() !in ALLOWED_SCHEMES) return "Only http and https URLs can be checked"
        val host = uri.host ?: return "The URL has no host"
        return resolutionProblem(host)
    }

    /**
     * Checks a connection, asking whatever question that kind of service can
     * actually answer.
     *
     * Slack is the reason this takes a type. Its Web API base is not an
     * endpoint — a HEAD on `https://slack.com/api` is permanently redirected to
     * the documentation site, which says nothing about the token and reads as a
     * problem when there is none. `auth.test` is the question worth asking: it
     * is what the token is for, and the answer says whether it works.
     */
    fun check(target: ConnectionTarget, type: ConnectionType?): CheckResult =
        if (type == ConnectionType.SLACK_SOCKET_MODE || type == ConnectionType.SLACK) {
            checkSlack(target)
        } else {
            check(target)
        }

    /**
     * `auth.test` with the bot token: 200 and `"ok":true` means the credential
     * works and Slack knows the team. `"ok":false` carries Slack's own error —
     * `invalid_auth`, `token_revoked` — which is the useful thing to show.
     *
     * A webhook connection has no token to test with, so it falls back to
     * asking whether the endpoint is there at all.
     */
    private fun checkSlack(target: ConnectionTarget): CheckResult {
        val token = target.secret?.trim()?.ifEmpty { null } ?: return check(target)
        val url = "${target.url.trimEnd('/')}/auth.test"
        vet(url)?.let { return CheckResult(CheckOutcome.FAILED, it) }

        val request = HttpRequest.newBuilder(URI(url))
            .timeout(Duration.ofSeconds(properties.probeTimeoutSeconds))
            .header("Authorization", "Bearer $token")
            .POST(HttpRequest.BodyPublishers.noBody())
            .build()

        return try {
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                return CheckResult(CheckOutcome.FAILED, "Slack answered ${response.statusCode()}")
            }
            val body = response.body()
            when {
                OK_TRUE.containsMatchIn(body) -> {
                    val team = TEAM.find(body)?.groupValues?.get(1)
                    CheckResult(CheckOutcome.CONNECTED, if (team == null) "Connected" else "Connected to $team")
                }
                else -> {
                    val error = SLACK_ERROR.find(body)?.groupValues?.get(1) ?: "it refused the token"
                    CheckResult(CheckOutcome.FAILED, "Slack rejected the token: $error")
                }
            }
        } catch (failure: Exception) {
            CheckResult(CheckOutcome.FAILED, failure.message ?: "Slack could not be reached")
        }
    }

    fun check(target: ConnectionTarget): CheckResult {
        vet(target.url)?.let { return CheckResult(CheckOutcome.FAILED, it) }
        val uri = URI(target.url)

        val builder = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(properties.probeTimeoutSeconds))
            // A HEAD asks whether the endpoint is there without acting on it.
            .method("HEAD", HttpRequest.BodyPublishers.noBody())
        target.requestHeaders().forEach { (name, value) ->
            runCatching { builder.header(name, value) }
        }

        return try {
            val response = client.send(builder.build(), HttpResponse.BodyHandlers.discarding())
            // Reachable is not the same as working, and a check that says
            // "successful" about an answer nobody would call success is worse
            // than no check. What the endpoint said is read instead: nothing
            // there, or broken, is a failure however well the socket opened.
            when (val status = response.statusCode()) {
                in 200..299 -> CheckResult(CheckOutcome.CONNECTED, "Answered $status")
                // A redirect is a service answering, and this probe does not
                // follow one — a redirect can leave the host the credentials
                // were meant for. So it counts as reachable and says where it
                // was sent, which is usually the URL that should have been
                // configured. Slack answers 301 to a HEAD on its API.
                in 300..399 -> CheckResult(
                    CheckOutcome.CONNECTED,
                    response.headers().firstValue("location").map { "Reachable; it redirects to $it" }
                        .orElse("Reachable; it answered $status"),
                )
                401, 403 -> CheckResult(CheckOutcome.FAILED, "The service rejected the credentials ($status)")
                404, 410 -> CheckResult(CheckOutcome.FAILED, "Nothing is served at that URL ($status)")
                // A HEAD is not what these endpoints are for — an MCP server or
                // a webhook takes a POST and says so. Refusing the question is
                // still an answer from the right service, so it counts.
                in NOT_FOR_A_HEAD -> CheckResult(CheckOutcome.CONNECTED, "Reachable; it does not answer a HEAD ($status)")
                in 500..599 -> CheckResult(CheckOutcome.FAILED, "The service is failing ($status)")
                else -> CheckResult(CheckOutcome.FAILED, "The service answered $status")
            }
        } catch (failure: Exception) {
            CheckResult(CheckOutcome.FAILED, failure.message ?: "The service could not be reached")
        }
    }

    /** Null when the host is fine to call. */
    private fun resolutionProblem(host: String): String? = try {
        val addresses = InetAddress.getAllByName(host)
        if (!properties.allowLinkLocal && addresses.any { it.isLinkLocalAddress || it.isAnyLocalAddress }) {
            "That host resolves to a link-local address"
        } else {
            null
        }
    } catch (_: Exception) {
        "The host could not be resolved"
    }

    private companion object {
        val ALLOWED_SCHEMES = setOf("http", "https")

        /**
         * Answers that mean "not like that" rather than "not here": the method,
         * the body type or the negotiation was refused, which only something
         * listening at that URL can refuse.
         */
        val NOT_FOR_A_HEAD = setOf(400, 405, 406, 415, 501)

        /** Slack answers every call with `ok`, and says why when it is false. */
        val OK_TRUE = Regex(""""ok"\s*:\s*true""")
        val SLACK_ERROR = Regex(""""error"\s*:\s*"([^"]+)"""")
        val TEAM = Regex(""""team"\s*:\s*"([^"]+)"""")
    }
}
