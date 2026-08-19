package io.mszymanski.orknux.server.mcp

import io.mszymanski.orknux.server.security.Role
import io.mszymanski.orknux.server.security.RoleRepository
import io.mszymanski.orknux.server.security.RoleScope
import io.mszymanski.orknux.server.user.AppUser
import io.mszymanski.orknux.server.user.AppUserRepository
import io.mszymanski.orknux.server.user.InternalAuthentication
import io.mszymanski.orknux.server.user.UserType
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.client.RestClient
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * What a wait costs the server.
 *
 * `orknux_news` holds its call open for as long as five minutes, because that is
 * the whole point of it: an assistant finds out that somebody replied on an
 * issue by having asked and still be listening. The question this class settles
 * is what else is holding on while it does.
 *
 * It used to be a thread. Tomcat has two hundred of them, anybody who can sign
 * in can ask for the longest wait there is, and two hundred such calls took the
 * server off the air for five minutes - through the one tool whose entire
 * purpose is to wait.
 *
 * **How that is proved, rather than asserted.** A claim that "no thread is held"
 * is not something an assertion can see directly, and a test that called the
 * tool and checked the answer would have passed before the change as happily as
 * after it. So the container is given a thread pool small enough to count on one
 * hand, more calls than that are put into a wait, and then an ordinary request
 * is timed. If a wait holds a thread there is none left for it and it queues
 * behind somebody's half minute; if a wait holds nothing, it is answered at
 * once. The difference is seconds, not milliseconds, and there is nothing in
 * between for a flaky machine to land on.
 *
 * **The wait is deliberately longer than half a minute.** Answering with a
 * promise puts the request in the container's hands, and the container gives up
 * on one after thirty seconds unless told otherwise - so the fix that makes a
 * wait cost nothing would have quietly capped the wait itself at less than the
 * tool offers. `spring.mvc.async.request-timeout` is what moves it, and a wait
 * of thirty-five seconds is what notices if anybody moves it back.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        // Six, so that twelve waiting calls cannot all be holding one.
        "server.tomcat.threads.max=6",
        "server.tomcat.threads.min-spare=2",
    ],
)
class McpNewsWaitTest(
    @LocalServerPort val port: Int,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val users: AppUserRepository,
    @Autowired val roles: RoleRepository,
    @Autowired val internal: InternalAuthentication,
) {

    private val client = RestClient.builder()
        .baseUrl("http://localhost:$port")
        .defaultStatusHandler({ true }, { _, _ -> })
        .build()

    private var workspaceId: Long = 0
    private lateinit var token: String

    /**
     * A workspace of its own each time and a token to reach it with.
     *
     * Nothing is deleted: this class shares a database with the rest of the
     * suite, and a fixture that empties tables it does not own is how a test
     * starts breaking its neighbours.
     */
    @BeforeEach
    fun arrive() {
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "mcp-news-${System.nanoTime()}")).id)

        // The authority an internal user's role produces is ROLE_<NAME>, and the
        // configured administrator authority is ROLE_ADMINS - which is what lets
        // this caller see the workspace it just made.
        val admins = roles.findByName(ADMINS)
            ?: roles.save(Role(name = ADMINS, scopes = mutableSetOf(RoleScope.ADMIN, RoleScope.USER)))
        val waiter = users.findByUsername(WAITER)
            ?: users.save(
                AppUser(
                    username = WAITER,
                    displayName = "The Waiter",
                    type = UserType.INTERNAL,
                    roles = mutableSetOf(admins),
                ),
            )
        token = internal.mint(waiter, "test-${System.nanoTime()}").second
    }

    @Test
    fun `calls that are waiting hold no thread, so the server keeps answering`() {
        val calling = Executors.newFixedThreadPool(WAITERS)
        try {
            val waiting: List<Future<ResponseEntity<String>>> =
                (1..WAITERS).map { calling.submit<ResponseEntity<String>> { newsCall(seconds = WAIT) } }

            // Long enough for all of them to have arrived and settled into the
            // wait, and short enough that none of them can have finished it.
            Thread.sleep(2_000)
            assertThat(waiting.count(Future<*>::isDone))
                .describedAs("the waiting calls must actually be waiting, or this test proves nothing")
                .isZero()

            val began = System.currentTimeMillis()
            val ordinary = client.get().uri("/api/auth/method").retrieve().toEntity(String::class.java)
            val took = System.currentTimeMillis() - began

            assertThat(ordinary.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(took)
                .describedAs("an ordinary request queued behind the waits, so the waits are holding threads")
                .isLessThan(2_000)

            // And the waits themselves still end, with an answer rather than a
            // timeout: holding nothing must not mean forgetting anybody.
            waiting.forEach { held ->
                val answered = held.get(60, TimeUnit.SECONDS)
                assertThat(answered.statusCode).isEqualTo(HttpStatus.OK)
                assertThat(answered.body).contains("\\\"waited\\\":$WAIT")
            }
        } finally {
            calling.shutdownNow()
        }
    }

    /** One `orknux_news` call over MCP, holding itself open for [seconds]. */
    private fun newsCall(seconds: Int): ResponseEntity<String> =
        client.post().uri("/mcp/$workspaceId")
            .contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
            .body(
                """
                {"jsonrpc":"2.0","id":1,"method":"tools/call",
                 "params":{"name":"orknux_news","arguments":{"wait":$seconds}}}
                """.trimIndent(),
            )
            .retrieve()
            .toEntity(String::class.java)

    private companion object {
        /** Twice the container's threads, so the old behaviour could not hide. */
        const val WAITERS = 12

        /** Past the thirty seconds a container waits by default; see above. */
        const val WAIT = 35

        const val ADMINS = "Admins"
        const val WAITER = "news-waiter"
    }
}
