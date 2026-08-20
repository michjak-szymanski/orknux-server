package io.mszymanski.orknux.server.monitoring

import io.mszymanski.orknux.server.user.AppUser
import io.mszymanski.orknux.server.user.AppUserRepository
import io.mszymanski.orknux.server.user.InternalAuthentication
import io.mszymanski.orknux.server.user.UserType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.client.RestClient

/**
 * Who may read the metrics, and what there is to read.
 *
 * Made over the real port rather than through a mock dispatcher, because the
 * question is which filter chain the request passes and what the Actuator
 * mapping does with it - neither of which is exercised by calling a controller.
 *
 * Two classes, because the answer is a configuration and one context holds one
 * answer. This is the shipped one: `/actuator/prometheus` needs somebody behind
 * the request, and there is nothing else under `/actuator` to ask for.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PrometheusEndpointTest(
    @LocalServerPort val port: Int,
    @Autowired val users: AppUserRepository,
    @Autowired val internal: InternalAuthentication,
) {

    private val client = restClient(port)

    private lateinit var token: String

    /**
     * A caller with no role at all, deliberately.
     *
     * A scrape is aggregate counters rather than anybody's data, so the line
     * drawn is "somebody this installation knows" and not "an administrator" -
     * a token that had to administer would be a far stronger credential to
     * leave sitting in a scrape configuration. This proves the weaker one is
     * enough, which is the thing an operator will rely on.
     */
    @BeforeEach
    fun mintAToken() {
        val scraper = users.findByUsername(SCRAPER)
            ?: users.save(AppUser(username = SCRAPER, displayName = "The Scraper", type = UserType.INTERNAL))
        token = internal.mint(scraper, "test-${System.nanoTime()}").second
    }

    @Test
    fun `refuses a scraper that has not signed in`() {
        assertThat(get(PROMETHEUS).statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `answers a caller carrying a token`() {
        val response = get(PROMETHEUS, token)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains("jvm_memory_used_bytes")
    }

    /**
     * The three series a fresh installation exposes before it has run anything.
     *
     * That they are here at all is the point: a counter registered on first use
     * would be absent until the first failure, and an alert cannot watch a
     * series that does not exist yet.
     */
    @Test
    fun `carries the run counters, reading zero, before any run`() {
        val body = requireNotNull(get(PROMETHEUS, token).body)

        assertThat(body).contains("orknux_workflow_runs_started")
        assertThat(body).contains("orknux_workflow_runs_finished")
        assertThat(body).contains("""outcome="completed"""", """outcome="failed"""")
    }

    /**
     * Exposure is a list of one. Everything else Actuator ships is unmapped
     * rather than merely protected, so it is a 404 for a caller who is known -
     * which is a stronger statement than a 401 would be.
     */
    @Test
    fun `exposes nothing else under actuator`() {
        assertThat(get("/actuator/health", token).statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(get("/actuator/env", token).statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    private fun get(path: String, bearer: String? = null) = get(client, path, bearer)

    private companion object {
        const val SCRAPER = "scraper"
    }
}

/**
 * The opt-in: `ORKNUX_METRICS_ANONYMOUS=true`, for a scrape that crosses a
 * network only the scraper is on.
 *
 * What it must open is one path and one method. The second test is the one worth
 * having - a rule written a little too wide is how "the metrics are reachable"
 * becomes "Actuator is reachable".
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["orknux.metrics.anonymous=true"],
)
class AnonymousPrometheusEndpointTest(@LocalServerPort val port: Int) {

    private val client = restClient(port)

    @Test
    fun `answers a scraper that cannot sign in`() {
        val response = get(client, PROMETHEUS)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains("orknux_workflow_runs_started")
    }

    @Test
    fun `opens nothing but the metrics`() {
        assertThat(get(client, "/actuator/health").statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(get(client, "/actuator/env").statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(get(client, "/api/session").statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }
}

private const val PROMETHEUS = "/actuator/prometheus"

/** Status handling is disabled so a refusal can be asserted on rather than thrown. */
private fun restClient(port: Int): RestClient = RestClient.builder()
    .baseUrl("http://localhost:$port")
    .defaultStatusHandler({ true }, { _, _ -> })
    .build()

private fun get(client: RestClient, path: String, bearer: String? = null): ResponseEntity<String> {
    val request = client.get().uri(path)
    if (bearer != null) request.header(HttpHeaders.AUTHORIZATION, "Bearer $bearer")
    return request.retrieve().toEntity(String::class.java)
}
