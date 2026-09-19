package io.mszymanski.orknux.server.plugin

import io.mszymanski.orknux.connector.proxy.ProxyRouter
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * One plugin the marketplace offers, exactly as it answered.
 *
 * Whether it is installed here is not on it: that is this server's own fact,
 * folded in where the two are put together. See [MarketplaceListingView].
 */
data class MarketplaceOffering(
    val key: String,
    val name: String,
    val author: String,
    val summary: String,
    val description: String,
    val version: String,
    val url: String,
    val icon: String?,
    val downloads: Int,
    val rating: Double?,
    val reviews: Int,
    val published: String,
)

/**
 * The marketplace, read through.
 *
 * This server holds no catalog: it asks the marketplace what is on offer and
 * answers with what came back. A marketplace that cannot be reached is a
 * refusal rather than an empty list — "nothing is offered" is not something a
 * network failure may claim, and a catalog screen that silently emptied would
 * be the worst way to say the site is down.
 *
 * The call goes through [ProxyRouter.builder], like every other outbound call
 * on this server, so an installation's proxy rules and trusted certificates
 * govern where it may reach. An installation with no marketplace configured
 * says so in a sentence rather than failing to start.
 */
@Component
class Marketplace(
    private val mapper: ObjectMapper,
    proxies: ProxyRouter,
    /** Where the marketplace's GraphQL lives; empty means this installation has none. */
    @Value("\${orknux.marketplace.url:https://orknux.io/graphql}") private val endpoint: String,
) {

    private val http: HttpClient = proxies.builder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    /** Whether this installation has a marketplace at all, for a screen to ask. */
    val configured: Boolean get() = endpoint.isNotBlank()

    fun offerings(refresh: Boolean = false): List<MarketplaceOffering> {
        val answer = asked(
            """
            query Catalog(${'$'}refresh: Boolean) {
              marketplacePlugins(refresh: ${'$'}refresh) { $FIELDS }
            }
            """.trimIndent(),
            mapOf("refresh" to refresh),
        )
        return answer.path("data").path("marketplacePlugins").values().map(::read)
    }

    /** One offering by key, or null where the catalog has none by that name. */
    fun offering(key: String): MarketplaceOffering? {
        val answer = asked(
            """
            query Offering(${'$'}key: String!) {
              marketplacePlugin(key: ${'$'}key) { $FIELDS }
            }
            """.trimIndent(),
            mapOf("key" to key),
        )
        val found = answer.path("data").path("marketplacePlugin")
        return if (found.isNull || found.isMissingNode) null else read(found)
    }

    private fun read(node: tools.jackson.databind.JsonNode) = MarketplaceOffering(
        key = node.path("key").asString(""),
        name = node.path("name").asString(""),
        author = node.path("author").asString(""),
        summary = node.path("summary").asString(""),
        description = node.path("description").asString(""),
        version = node.path("version").asString(""),
        url = node.path("url").asString(""),
        icon = node.path("icon").asString("").ifEmpty { null },
        downloads = node.path("downloads").asInt(0),
        rating = node.path("rating").takeIf { it.isNumber }?.asDouble(),
        reviews = node.path("reviews").asInt(0),
        published = node.path("published").asString(""),
    )

    /**
     * One query, and the marketplace's own words when it refuses.
     *
     * A GraphQL error is carried out as the sentence it is: the caller is a
     * screen, and "the marketplace said X" is worth more than a stack trace
     * about a JSON node that was not there.
     */
    private fun asked(query: String, variables: Map<String, Any?>): tools.jackson.databind.JsonNode {
        if (!configured) throw MarketplaceUnreachableException("this installation has no marketplace configured")

        val body = mapper.writeValueAsString(mapOf("query" to query, "variables" to variables))
        val request = HttpRequest.newBuilder(URI.create(endpoint))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val answer = try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (failure: java.io.IOException) {
            throw MarketplaceUnreachableException(failure.message ?: "it could not be reached")
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw MarketplaceUnreachableException("the request was interrupted")
        }
        if (answer.statusCode() != 200) {
            throw MarketplaceUnreachableException("it answered ${answer.statusCode()}")
        }

        val read = runCatching { mapper.readTree(answer.body()) }.getOrNull()
            ?: throw MarketplaceUnreachableException("it answered something that is not JSON")
        val errors = read.path("errors")
        if (errors.isArray && !errors.isEmpty) {
            throw MarketplaceUnreachableException(errors.first().path("message").asString("it refused the query"))
        }
        return read
    }

    private companion object {
        val FIELDS = "key name author summary description version url icon downloads rating reviews published"
    }
}

/** The marketplace could not be asked, in the words it or the network used. */
class MarketplaceUnreachableException(why: String) : RuntimeException("The marketplace could not be read: $why.")
