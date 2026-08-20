package io.mszymanski.orknux.server.monitoring

import io.mszymanski.orknux.server.attachment.InstallationSettingRepository
import io.mszymanski.orknux.server.attachment.InstallationSettings
import io.mszymanski.orknux.server.attachment.SettingNames
import io.mszymanski.orknux.server.user.AppUser
import io.mszymanski.orknux.server.user.AppUserRepository
import io.mszymanski.orknux.server.user.InternalAuthentication
import io.mszymanski.orknux.server.user.UserType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpHeaders
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.test.context.support.WithMockUser
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
class AnonymousPrometheusEndpointTest(
    @LocalServerPort val port: Int,
    @Autowired val settings: InstallationSettings,
    @Autowired val held: InstallationSettingRepository,
) {

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

    /**
     * The half of the arrangement worth writing down: the file says where a
     * fresh installation starts, and an administrator who has since said no is
     * not overruled by it on the next restart.
     */
    @Test
    fun `what an administrator stored beats what the file says`() {
        settings.setMetricsAnonymous(false, "test")

        assertThat(get(client, PROMETHEUS).statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @AfterEach
    fun forgetTheSwitch() = forgetTheSwitch(held)
}

/**
 * The switch, pressed while the server is running.
 *
 * This is the one that would silently not work. Every other rule in the chain is
 * settled when the chain is built, and a metrics rule written the same way would
 * pass a test that only ever asked it once — the endpoint would be open or shut
 * according to the configuration the context started with, and pressing the
 * switch would appear to do nothing until somebody restarted the server and
 * concluded it had worked all along.
 *
 * So the flip happens between two scrapes of the same running server, over the
 * real port, and the answer has to change both ways.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class MetricsSwitchTest(
    @LocalServerPort val port: Int,
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val held: InstallationSettingRepository,
) {

    private val client = restClient(port)

    @Test
    fun `pressing it changes the answer on the next scrape, with no restart`() {
        assertThat(get(client, PROMETHEUS).statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)

        press(true)
        assertThat(get(client, PROMETHEUS).statusCode).isEqualTo(HttpStatus.OK)

        // And back, because a switch that only goes one way is a door.
        press(false)
        assertThat(get(client, PROMETHEUS).statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `opens the metrics and nothing else when it is on`() {
        press(true)

        assertThat(get(client, PROMETHEUS).statusCode).isEqualTo(HttpStatus.OK)
        assertThat(get(client, "/actuator/env").statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(get(client, "/api/session").statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    /** Through the mutation an administrator's screen calls, not the service behind it. */
    private fun press(enabled: Boolean) {
        graphQlTester
            .document("mutation { setMetricsAnonymous(enabled: $enabled) { metricsAnonymous } }")
            .execute()
            .path("setMetricsAnonymous.metricsAnonymous")
            .entity(Boolean::class.java)
            .isEqualTo(enabled)
    }

    @AfterEach
    fun forgetTheSwitch() = forgetTheSwitch(held)
}

private const val PROMETHEUS = "/actuator/prometheus"

/**
 * Puts the switch back, so the next class in the suite starts where a fresh
 * installation would. Deleted rather than set to false: absent is what "nobody
 * has pressed it" looks like, and that is the state being restored.
 */
private fun forgetTheSwitch(held: InstallationSettingRepository) {
    held.findById(SettingNames.METRICS_ANONYMOUS).ifPresent(held::delete)
}

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
