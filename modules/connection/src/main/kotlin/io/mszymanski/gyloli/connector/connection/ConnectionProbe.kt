package io.mszymanski.gyloli.connector.connection

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Service
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64

@ConfigurationProperties(prefix = "gyloli.connection")
data class ConnectionProperties(
    /** How long a probe may take before it counts as a failure. */
    val probeTimeoutSeconds: Long = 5,
    /**
     * Link-local addresses reach cloud instance metadata, so a connection URL
     * resolving to one is refused rather than fetched. Private and loopback
     * addresses stay allowed: internal services are the point of the feature.
     */
    val allowLinkLocal: Boolean = false,
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
 * exists: gyloli-workflow asks for a target rather than for a secret, and the
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

fun TeamConnection.target(): ConnectionTarget =
    ConnectionTarget(effectiveUrl, authType, secret, headers.toList())

fun McpServer.target(): ConnectionTarget = ConnectionTarget(address, authType, secret, headers.toList())

/**
 * Checks that a connection actually answers, so the team screen reports what was
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

    fun check(target: ConnectionTarget): CheckResult {
        val uri = try {
            URI(target.url)
        } catch (_: Exception) {
            return CheckResult(CheckOutcome.FAILED, "The URL is not valid")
        }

        if (uri.scheme?.lowercase() !in ALLOWED_SCHEMES) {
            return CheckResult(CheckOutcome.FAILED, "Only http and https URLs can be checked")
        }
        val host = uri.host ?: return CheckResult(CheckOutcome.FAILED, "The URL has no host")
        resolutionProblem(host)?.let { return CheckResult(CheckOutcome.FAILED, it) }

        val builder = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(properties.probeTimeoutSeconds))
            // A HEAD asks whether the endpoint is there without acting on it.
            .method("HEAD", HttpRequest.BodyPublishers.noBody())
        target.requestHeaders().forEach { (name, value) ->
            runCatching { builder.header(name, value) }
        }

        return try {
            val response = client.send(builder.build(), HttpResponse.BodyHandlers.discarding())
            // Any answer proves the endpoint is reachable, which is all a probe
            // can establish; only a refusal to authenticate is a real failure.
            when (val status = response.statusCode()) {
                401, 403 -> CheckResult(CheckOutcome.FAILED, "The service rejected the credentials ($status)")
                else -> CheckResult(CheckOutcome.CONNECTED, "Answered with $status")
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
    }
}
