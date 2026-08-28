package io.mszymanski.orknux.connector.model

import com.openai.azure.AzureUrlPathMode
import com.openai.azure.credential.AzureApiKeyCredential
import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.core.http.ProxyAuthenticator
import com.openai.credential.BearerTokenCredential
import com.openai.credential.Credential
import io.mszymanski.orknux.connector.proxy.ProxyChoice
import io.mszymanski.orknux.connector.proxy.ProxyRouter
import org.springframework.stereotype.Component
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Supplier

/**
 * Hands out the SDK client a provider is called through.
 *
 * **Why the SDK rather than a URL built here.** Every provider but Anthropic
 * speaks the OpenAI shape, and the one that varies is Azure - which serves two
 * different URL layouts from a single resource. The older puts the deployment
 * and an API version in the path; the newer is the OpenAI shape unchanged under
 * `/openai/v1`, addressing the model by name with no version at all. Which one
 * a resource answers on depends on the resource and on the model deployed to
 * it, and Azure adds API versions every few months. A path assembled here
 * encodes whichever arrangement was true the day it was written, and a resource
 * serving the other answers `404 Resource not found` - which reads as a wrong
 * deployment name and sends whoever is debugging to a field that was correct.
 * [AzureUrlPathMode.AUTO] is the SDK deciding that from the address, maintained
 * by the people who change the API. See AGENTS.md.
 *
 * **Why the clients are cached.** An [OpenAIClient] carries an OkHttp connection
 * pool and its dispatcher threads, so one per request would open a new pool for
 * every message. The key is everything that decides who is being called and as
 * whom, so pointing a provider somewhere else - or rotating its secret - stops
 * using the old client rather than going on with the old connections.
 *
 * **Why the proxy is passed in explicitly.** [ProxyRouter]'s guarantee is that
 * a client it did not build is a client the rules do not reach, and this is one
 * it cannot build: the SDK brings its own OkHttp stack. So the rule is resolved
 * here for the base URL and handed over as a [Proxy] and, where the rule holds
 * an account, a [ProxyAuthenticator] - the same road already taken for Slack's
 * SDK, ending at the same compiled rules in the same order.
 */
@Component
class ModelClients(private val proxies: ProxyRouter) {

    private val cache = ConcurrentHashMap<ClientKey, OpenAIClient>()

    /**
     * The client for this provider, built once.
     *
     * [credential] is resolved by the caller because reading it can fail in ways
     * worth reporting - a workspace secret that has been deleted, a token grant
     * Entra refused - and those are sentences a provider's card shows rather
     * than exceptions to throw here.
     */
    fun clientFor(provider: ModelProvider, credential: Credential): OpenAIClient {
        val base = provider.openAiBase()
        return cache.computeIfAbsent(ClientKey(base, provider.type, identity(credential))) {
            build(base, provider, credential)
        }
    }

    /** Forget every built client, so the next call reads the rules again. */
    fun reload() = cache.clear()

    private fun build(base: String, provider: ModelProvider, credential: Credential): OpenAIClient {
        val builder = OpenAIOkHttpClient.builder()
            .baseUrl(base)
            .credential(credential)
            /*
             * Asked once, because somebody else is counting.
             *
             * This application has its own retry policy - a node's, with its own
             * backoff, its own ceiling and a screen that shows the attempts. The
             * SDK ships one too, and two of them compose by multiplying: a step
             * allowed three attempts made nine, and a request the provider had
             * flatly refused was sent again twice before anything here was told
             * about it. Whether a failure is worth repeating is a decision this
             * application already makes and can explain, so the SDK is asked to
             * make it no more.
             */
            .maxRetries(0)

        // Azure decides its own URL layout from the address it was given.
        if (provider.type == ProviderType.AZURE_OPENAI) builder.azureUrlPathMode(AzureUrlPathMode.AUTO)

        proxies.resolve(base)?.let { rule ->
            builder.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(rule.host, rule.port)))
            rule.username?.let { user -> builder.proxyAuthenticator(ProxyAuthenticator.basic(user, rule.password.orEmpty())) }
        }

        return builder.build()
    }

    /**
     * What decides the client is a different one.
     *
     * A credential's own identity rather than the object: two [Credential]s
     * holding the same key are the same caller, and a [BearerTokenCredential]
     * built around a supplier is one caller whose token changes underneath it -
     * which is the point of the supplier, and why the token itself is not part
     * of this.
     */
    private fun identity(credential: Credential): String = when (credential) {
        is AzureApiKeyCredential -> "azure-key:${credential.apiKey()}"
        is BearerTokenCredential -> "bearer"
        else -> credential.javaClass.name
    }

    private data class ClientKey(val base: String, val type: ProviderType, val credential: String)

    companion object {
        /** A token read afresh on every call, so a rotated one is picked up. */
        fun bearer(token: Supplier<String>): Credential = BearerTokenCredential.create(token)

        /** Azure's own key header, which is not `Authorization`. */
        fun azureKey(key: String): Credential = AzureApiKeyCredential.create(key)

        /** Every other provider: a key sent as a bearer token. */
        fun apiKey(key: String): Credential = BearerTokenCredential.create(key)
    }
}
