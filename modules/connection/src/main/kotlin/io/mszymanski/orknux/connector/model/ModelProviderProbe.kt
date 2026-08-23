package io.mszymanski.orknux.connector.model

import io.mszymanski.orknux.connector.connection.CheckOutcome
import io.mszymanski.orknux.connector.connection.CheckResult
import io.mszymanski.orknux.connector.connection.ConnectionProbe
import io.mszymanski.orknux.connector.connection.ConnectionProperties
import io.mszymanski.orknux.connector.connection.HttpHeader
import io.mszymanski.orknux.connector.proxy.ProxyRouter
import io.mszymanski.orknux.connector.security.SecretCipher
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import java.net.URLEncoder
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Checks that a provider answers, so "Connection successful" means the provider
 * said so rather than that a key was typed in.
 *
 * What is safe to call is [ConnectionProbe]'s decision, kept in one place. What
 * is asked is this one's: where each type wants its key, the token request
 * Entra ID needs before there is a credential at all, and a question whose
 * answer is worth reporting.
 */
@Service
class ModelProviderProbe(
    private val probe: ConnectionProbe,
    private val properties: ConnectionProperties,
    private val mapper: ObjectMapper,
    /** Only to recognise a credential that never came out of its envelope. */
    private val cipher: SecretCipher,
    private val proxies: ProxyRouter,
) {

    /**
     * Service principal tokens, while they last. Concurrent because a sweep, a
     * chat and a button press can all want one at the same moment.
     */
    private val cached = ConcurrentHashMap<TokenKey, HeldToken>()

    private val client: HttpClient = proxies.builder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(properties.probeTimeoutSeconds))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    /**
     * Asks the provider to list its models.
     *
     * Not a HEAD on whatever URL was typed in. "Something answered" is a poor
     * thing to call a connection: a model provider that returns 404 for its
     * model list is one the chat cannot use, and reporting that as success is
     * how you get told "Connection successful — Answered with 404". Listing
     * models proves three things at once — the host is reachable, the
     * credential is accepted, and the thing at the other end is a model API.
     */
    fun check(provider: ModelProvider): CheckResult = when (val listing = list(provider)) {
        is Listing.Failed -> CheckResult(CheckOutcome.FAILED, listing.reason)
        is Listing.Models -> CheckResult(CheckOutcome.CONNECTED, listed(listing.ids))
    }

    /**
     * What the provider says it can run.
     *
     * The same request the check makes, because they are the same question: the
     * check wants to know whether an answer came back, and discovery wants what
     * was in it. Counting the models and throwing the list away would mean
     * asking twice for something already in hand.
     */
    fun list(provider: ModelProvider): Listing {
        val credential = when (val resolved = credentials(provider)) {
            is Credential.Failed -> return Listing.Failed(resolved.reason)
            is Credential.Header -> resolved.header
        }

        val url = modelsUrl(provider)
        probe.vet(url)?.let { return Listing.Failed(it) }

        val request = HttpRequest.newBuilder(URI(url))
            .timeout(Duration.ofSeconds(properties.probeTimeoutSeconds))
            .header(credential.name, credential.value)
            .GET()

        if (provider.type == ProviderType.ANTHROPIC) request.header("anthropic-version", ANTHROPIC_VERSION)

        return try {
            val response = client.send(request.build(), HttpResponse.BodyHandlers.ofString())
            when (val status = response.statusCode()) {
                in 200..299 -> Listing.Models(names(response.body()))
                401, 403 -> Listing.Failed(
                    "The provider rejected the credentials ($status)" + said(response.body()),
                )
                404 -> Listing.Failed("No model list at $url — check the endpoint")
                else -> Listing.Failed("The provider answered $status" + said(response.body()))
            }
        } catch (failure: Exception) {
            Listing.Failed(failure.message ?: "The provider could not be reached")
        }
    }

    /** What asking a provider for its models produced. */
    sealed interface Listing {
        /** What it offers. Empty is an answer too: a server with nothing loaded. */
        data class Models(val ids: List<String>) : Listing
        data class Failed(val reason: String) : Listing
    }

    /**
     * Where a provider says what it can run.
     *
     * Azure puts the version in the query; everything else lists at `/models` of
     * its OpenAI surface, which is not always the endpoint that was typed in -
     * see [ModelProvider.openAiBase].
     */
    private fun modelsUrl(provider: ModelProvider): String {
        return if (provider.type == ProviderType.AZURE_OPENAI) {
            val base = provider.endpoint.trimEnd('/')
            val version = provider.apiVersion?.ifBlank { null } ?: DEFAULT_AZURE_VERSION
            "$base/openai/models?api-version=$version"
        } else {
            "${provider.openAiBase()}/models"
        }
    }

    /**
     * How many models it offered. Saying the number is worth more than saying
     * "connected": it is the difference between a provider that works and one
     * that works and has nothing on it.
     */
    private fun listed(ids: List<String>): String = when (ids.size) {
        0 -> "Connected; it listed no models"
        1 -> "Connected; 1 model listed"
        else -> "Connected; ${ids.size} models listed"
    }

    /**
     * The model names out of a listing, whichever shape the provider answers in.
     *
     * `data[].id` is the OpenAI shape, which Anthropic, Azure OpenAI and most
     * self-hosted servers also speak. Ollama's own listing is `models[].name`,
     * and llama.cpp answers with both — so `data` is read first and `models`
     * only when there was none, rather than merging two spellings of one list.
     */
    private fun names(body: String): List<String> {
        val tree = try {
            mapper.readTree(body)
        } catch (_: Exception) {
            return emptyList()
        }

        val openAi = tree.path("data").mapNotNull { it.path("id").stringValue() }
        if (openAi.isNotEmpty()) return openAi.distinct()

        return tree.path("models")
            .mapNotNull { entry -> entry.path("name").stringValue() ?: entry.path("model").stringValue() }
            .distinct()
    }

    /**
     * What the other end said about the refusal, ready to append, or nothing.
     *
     * A status on its own says the credential was refused but not which
     * credential or why, and every one of these services is willing to say:
     * Entra answers a bad client secret with `AADSTS7000215: Invalid client
     * secret provided`, which is the difference between retyping a secret and
     * hunting a tenant. Three spellings because three vendors chose three -
     * `error_description` is Entra's, `error.message` is OpenAI's and Azure
     * OpenAI's, `message` is what most self-hosted servers answer with - and a
     * body that is none of them, or is not JSON at all, adds nothing rather than
     * putting a page of HTML on the screen.
     *
     * Trimmed to a sentence's worth. Entra's description carries a correlation
     * id, a timestamp and a URL after the part worth reading.
     */
    private fun said(body: String): String {
        val tree = try {
            mapper.readTree(body)
        } catch (_: Exception) {
            return ""
        }

        val error = tree.path("error")
        // stringValueOpt rather than stringValue: the latter throws on a node
        // that is not a string, and every one of these is a guess about a body
        // this code did not write.
        val message = listOf(tree.path("error_description"), error.path("message"), error, tree.path("message"))
            .firstNotNullOfOrNull { it.stringValueOpt().orElse(null) }
            ?: return ""

        val said = message.trim().lineSequence().first().trim().take(MESSAGE_LIMIT)
        return if (said.isEmpty()) "" else ": $said"
    }

    /**
     * The header that authenticates a call to this provider.
     *
     * Both the check and an actual call need it, and it is resolved here so the
     * stored credential is read in one place — including the Entra ID case,
     * where there is no credential to send until a token has been fetched with
     * one.
     */
    fun credentials(provider: ModelProvider): Credential {
        if (!provider.configured()) {
            return Credential.Failed("There are no credentials to call this provider with")
        }

        /*
         * Stored, but not with the key this installation has now.
         *
         * Sending it as it stands would put the envelope in the header and come
         * back a 401, which reads as a wrong credential rather than an
         * unreadable one — and those two want opposite things done about them.
         */
        if (cipher.isEncrypted(provider.secret)) {
            return Credential.Failed(
                "This provider's credential cannot be read with the current secret key. " +
                    "Enter it again, or restore the key it was saved with.",
            )
        }

        return when (provider.authMethod) {
            ProviderAuthMethod.API_KEY -> Credential.Header(keyHeader(provider))
            ProviderAuthMethod.ENTRA_ID -> when (val token = entraToken(provider)) {
                is EntraToken.Failed -> Credential.Failed(token.reason)
                is EntraToken.Issued -> Credential.Header(HttpHeader("Authorization", "Bearer ${token.value}"))
            }
        }
    }

    /** What authenticating resolved to: a header to send, or why there is none. */
    sealed interface Credential {
        data class Header(val header: HttpHeader) : Credential
        data class Failed(val reason: String) : Credential
    }

    /** Each service wants the key somewhere different, and none of them are wrong. */
    private fun keyHeader(provider: ModelProvider): HttpHeader {
        val key = provider.secret.orEmpty()
        return when (provider.type) {
            ProviderType.ANTHROPIC -> HttpHeader("x-api-key", key)
            ProviderType.AZURE_OPENAI -> HttpHeader("api-key", key)
            else -> HttpHeader("Authorization", "Bearer $key")
        }
    }

    /**
     * A token for the service principal, from cache when one is still good.
     *
     * Entra issues these for about an hour, and a provider authenticating this
     * way needs one on every call — every chat message, every check. Fetching
     * one each time adds a round trip to Microsoft in front of each request and
     * walks into the token endpoint's throttling for no benefit whatsoever.
     *
     * Keyed by the whole credential, so rotating the secret or pointing at a
     * different tenant, application or scope does not go on using the old
     * token. Expiry is what Entra said, less a minute: a token that expires
     * while a request is in the air is a failure nobody can explain.
     */
    private fun entraToken(provider: ModelProvider): EntraToken {
        val key = TokenKey(
            tenantId = provider.tenantId.orEmpty(),
            clientId = provider.clientId.orEmpty(),
            secret = provider.secret.orEmpty(),
            scope = provider.scope?.ifBlank { null } ?: DEFAULT_SCOPE,
        )
        cached[key]?.let { held ->
            if (held.goodUntil.isAfter(Instant.now())) return EntraToken.Issued(held.value)
            cached.remove(key, held)
        }

        return when (val fetched = fetchEntraToken(provider)) {
            is EntraToken.Failed -> fetched
            is EntraToken.Issued -> {
                cached[key] = HeldToken(fetched.value, Instant.now().plusSeconds(fetched.seconds - EXPIRY_MARGIN))
                fetched
            }
        }
    }

    /** What a token is for: change any of it and the old one is the wrong one. */
    private data class TokenKey(
        val tenantId: String,
        val clientId: String,
        val secret: String,
        val scope: String,
    )

    private data class HeldToken(val value: String, val goodUntil: Instant)

    /** The client credentials grant itself. */
    private fun fetchEntraToken(provider: ModelProvider): EntraToken {
        val address = "${properties.entraAuthority.trimEnd('/')}/${provider.tenantId}/oauth2/v2.0/token"

        /*
         * Asked where it is going, like every other outbound call here.
         *
         * The authority is an installation's setting rather than something a
         * workspace member types, so whoever can point this somewhere is an
         * administrator already - which made it the easiest one to leave out and
         * the last one that was. It is still a POST carrying the application's
         * client secret, and an address nobody vetted is an address that can be
         * a link-local one, which is where a cloud instance hands out its own
         * credentials. A secret posted there is gone.
         *
         * Before the form is built, so a call that will not be made does not
         * assemble the secret to send. The reason is returned rather than
         * logged, the way [ModelChatClient] returns its own: it travels out
         * through the credential as the failure the provider check and the chat
         * put in front of whoever configured this.
         */
        probe.vet(address)?.let { return EntraToken.Failed("The Entra ID authority cannot be called: $it") }

        val uri = try {
            URI(address)
        } catch (_: Exception) {
            return EntraToken.Failed("The tenant is not usable in a token URL")
        }

        val form = listOf(
            "grant_type" to "client_credentials",
            "client_id" to provider.clientId.orEmpty(),
            "client_secret" to provider.secret.orEmpty(),
            "scope" to (provider.scope?.ifBlank { null } ?: DEFAULT_SCOPE),
        ).joinToString("&") { (name, value) -> "$name=" + URLEncoder.encode(value, StandardCharsets.UTF_8) }

        val request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(properties.probeTimeoutSeconds))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form))
            .build()

        return try {
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) {
                return EntraToken.Failed(
                    "Entra ID refused the credentials (${response.statusCode()})" + said(response.body()),
                )
            }
            val answer = mapper.readTree(response.body())
            val token = answer.path("access_token").stringValue()
                ?: return EntraToken.Failed("Entra ID answered without a token")
            // How long it is good for, which is what makes caching it safe.
            // Entra always says; a default only covers it never having to.
            val seconds = answer.path("expires_in").asLong(DEFAULT_TOKEN_SECONDS)
            EntraToken.Issued(token, seconds)
        } catch (failure: Exception) {
            EntraToken.Failed(failure.message ?: "Entra ID could not be reached")
        }
    }

    private sealed interface EntraToken {
        data class Issued(val value: String, val seconds: Long = DEFAULT_TOKEN_SECONDS) : EntraToken
        data class Failed(val reason: String) : EntraToken
    }

    private companion object {
        /** What Azure OpenAI asks for when nothing else is said. */
        const val DEFAULT_SCOPE = "https://cognitiveservices.azure.com/.default"

        const val ANTHROPIC_VERSION = "2023-06-01"
        const val DEFAULT_AZURE_VERSION = "2024-06-01"

        /** What Entra says when it says nothing: an hour is its usual answer. */
        const val DEFAULT_TOKEN_SECONDS = 3600L

        /** Held back from the expiry, so a token cannot lapse mid-request. */
        const val EXPIRY_MARGIN = 60L

        /** How much of a provider's own complaint is worth repeating. */
        const val MESSAGE_LIMIT = 200
    }
}
