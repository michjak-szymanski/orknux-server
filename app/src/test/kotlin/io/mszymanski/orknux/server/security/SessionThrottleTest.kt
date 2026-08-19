package io.mszymanski.orknux.server.security

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.client.RestClient

/**
 * The throttle where it actually stands: on the one door anybody may knock on.
 *
 * `POST /api/session` is open by necessity - it is how somebody signs in - and
 * until this it counted nothing, so a username somebody knew existed could be
 * tried at whatever rate the network allowed, and under LDAP every try landed on
 * the directory too.
 *
 * The context is given its own numbers, small and fast, for two reasons: a test
 * should not sit through the real two-second pause, and its own numbers mean its
 * own application context, so the counters are not shared with whatever else in
 * the suite signs in and gets it wrong.
 *
 * The per-address allowance is deliberately far out of reach here. What that
 * rule does is pinned down in [SignInThrottleTest]; leaving it in the way would
 * only make these harder to read.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "orknux.security.sign-in.per-username=3",
        "orknux.security.sign-in.per-address=200",
        "orknux.security.sign-in.first-wait=1s",
    ],
)
class SessionThrottleTest(@LocalServerPort val port: Int) {

    /** Status handling is off, so a refusal can be asserted on rather than thrown. */
    private val client = RestClient.builder()
        .baseUrl("http://localhost:$port")
        .defaultStatusHandler({ true }, { _, _ -> })
        .build()

    /**
     * A name nobody has, on purpose. Guessing at usernames is half of what this
     * is for, and it keeps the counting out of the way of the tests below.
     */
    @Test
    fun `enough wrong passwords and the door stops answering`() {
        repeat(3) {
            assertThat(login("mallory", "wrong-password").statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        }

        val refused = login("mallory", "wrong-password")

        assertThat(refused.statusCode).isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
        // Told how long, rather than left to guess or to keep knocking.
        assertThat(refused.headers.getFirst(HttpHeaders.RETRY_AFTER)).isNotNull()
    }

    /**
     * The half a throttle usually gets wrong: everybody else's morning.
     *
     * One name being hammered must not be felt by anybody who is signing in
     * correctly, or the throttle has become the outage it was meant to prevent.
     */
    @Test
    fun `somebody else being throttled is not felt by a normal sign-in`() {
        repeat(4) { login("trudy", "wrong-password") }

        val alice = login("alice", "password")

        assertThat(alice.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(alice.body).contains("alice")
    }

    /**
     * And the half that keeps it from being a lockout: the wait ends, and the
     * person it was aimed at gets in with the password they should have typed in
     * the first place.
     */
    @Test
    fun `the wait ends and the right password still works`() {
        repeat(3) { login("bob", "wrong-password") }
        assertThat(login("bob", "wrong-password").statusCode).isEqualTo(HttpStatus.TOO_MANY_REQUESTS)

        // The first wait is a second here, so a second and a half is the pause
        // plus enough slack for a machine having a bad moment.
        Thread.sleep(1_500)

        val bob = login("bob", "password")
        assertThat(bob.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(bob.body).contains("bob")
    }

    /**
     * Read as text, because a refusal answers with an error object rather than a
     * user and asking for one back would fail while being deserialised instead
     * of being asserted on.
     */
    private fun login(username: String, password: String): ResponseEntity<String> =
        client.post().uri("/api/session")
            .contentType(MediaType.APPLICATION_JSON)
            .body(LoginRequest(username, password))
            .retrieve()
            .toEntity(String::class.java)
}
