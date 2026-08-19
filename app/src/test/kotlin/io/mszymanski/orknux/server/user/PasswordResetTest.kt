package io.mszymanski.orknux.server.user

import io.mszymanski.orknux.server.action.RecordingMailTransport
import io.mszymanski.orknux.server.security.SignInThrottle
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.web.client.RestClient
import java.security.MessageDigest
import java.time.Duration
import java.time.OffsetDateTime
import java.util.HexFormat

/**
 * Forgetting a password and getting back in.
 *
 * Everything is real except the mail server. [RecordingMailTransport] stands in
 * for it, which is the same seam the Send Email action is tested through - so
 * the suite reads the link out of the message that would have been sent and puts
 * nobody's address anywhere near a real relay.
 *
 * The base URL and the installation's mail server are set as properties here,
 * because they are an operator's configuration rather than anything stored: an
 * installation with neither cannot offer this at all, which is the point of
 * having them.
 *
 * Two of these go through HTTP rather than calling the service, and both are
 * about something only the endpoint can show: that the answer is the same
 * sentence whoever was asked about, and that a session held in the database
 * elsewhere stops working when the password behind it changes.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "orknux.mail.host=localhost",
        "orknux.mail.from=orknux@localhost",
        "orknux.web.base-url=https://orknux.example",
    ],
)
class PasswordResetTest(
    @LocalServerPort val port: Int,
    @Autowired val resets: PasswordResetService,
    @Autowired val stored: PasswordResetRepository,
    @Autowired val users: AppUserRepository,
    @Autowired val internal: InternalAuthentication,
    @Autowired val encoder: PasswordEncoder,
    @Autowired val transport: RecordingMailTransport,
    @Autowired @Qualifier("passwordResetThrottle") val throttle: SignInThrottle,
) {

    /** Status handling is off so a refusal can be asserted on rather than thrown. */
    private val client = RestClient.builder()
        .baseUrl("http://localhost:$port")
        .defaultStatusHandler({ true }, { _, _ -> })
        .build()

    @BeforeEach
    fun reset() {
        stored.deleteAll()
        users.deleteAll()
        transport.forget()
        /*
         * The throttle is a bean and keeps its counters in memory, so what one
         * test spent would otherwise be charged to the next. Cleared for every
         * key these tests use, which is what `succeeded` does.
         */
        listOf(HERS, NOBODYS, DIRECTORY, CALLER).forEach { throttle.succeeded(it, CALLER) }
    }

    @Test
    fun `a reset for an internal user sends a link, and the link works once`() {
        val held = internalUser("helpdesk", HERS, "the first password")

        resets.request(HERS, CALLER)

        val message = await().atMost(Duration.ofSeconds(5)).until({ transport.sent.firstOrNull() }) { it != null }!!
        assertThat(message.second.to).containsExactly(HERS)
        assertThat(message.second.body).contains("https://orknux.example/reset-password?token=")

        val username = resets.complete(tokenIn(message.second.body), "a new long password", CALLER)

        assertThat(username).isEqualTo("helpdesk")
        assertThat(internal.authenticate("helpdesk", "a new long password")).isNotNull()
        assertThat(internal.authenticate("helpdesk", "the first password")).isNull()
        assertThat(stored.findByUserId(requireNotNull(held.id)).single().usedAt).isNotNull()
    }

    @Test
    fun `a link that has been used is refused the second time`() {
        internalUser("helpdesk", HERS, "the first password")
        resets.request(HERS, CALLER)
        val token = tokenIn(mailed())

        resets.complete(token, "a new long password", CALLER)

        assertThatThrownBy { resets.complete(token, "another long password", CALLER) }
            .isInstanceOf(PasswordResetInvalidException::class.java)
        // The password is the one the first use set, not the second attempt's.
        assertThat(internal.authenticate("helpdesk", "another long password")).isNull()
        assertThat(internal.authenticate("helpdesk", "a new long password")).isNotNull()
    }

    @Test
    fun `a link that has expired is refused`() {
        val held = internalUser("helpdesk", HERS, "the first password")
        /*
         * Written straight into the table with an expiry in the past rather than
         * waiting an hour for one issued the ordinary way. What is being asked is
         * whether the row's own expiry is read when the link is followed, and the
         * clock is not part of that question.
         */
        stored.save(
            PasswordReset(
                userId = requireNotNull(held.id),
                tokenHash = hashOf("a token from last week"),
                expiresAt = OffsetDateTime.now().minusMinutes(1),
            ),
        )

        assertThatThrownBy { resets.complete("a token from last week", "a new long password", CALLER) }
            .isInstanceOf(PasswordResetInvalidException::class.java)
        assertThat(internal.authenticate("helpdesk", "the first password")).isNotNull()
    }

    @Test
    fun `asking for a second link kills the first`() {
        internalUser("helpdesk", HERS, "the first password")
        resets.request(HERS, CALLER)
        val first = tokenIn(mailed())

        transport.forget()
        resets.request(HERS, CALLER)
        val second = tokenIn(mailed())

        assertThatThrownBy { resets.complete(first, "a new long password", CALLER) }
            .isInstanceOf(PasswordResetInvalidException::class.java)
        assertThat(resets.complete(second, "a new long password", CALLER)).isEqualTo("helpdesk")
    }

    @Test
    fun `an address nobody has is answered exactly as one somebody has`() {
        internalUser("helpdesk", HERS, "the first password")

        val known = ask(HERS)
        val unknown = ask(NOBODYS)

        assertThat(unknown.statusCode).isEqualTo(known.statusCode)
        assertThat(unknown.body).isEqualTo(known.body)
        assertThat(known.body).contains("If that address belongs to an account, a link is on its way.")
    }

    @Test
    fun `a directory user's address gets no link`() {
        users.save(AppUser(username = "alice", displayName = "Alice", type = UserType.EXTERNAL, email = DIRECTORY))
        internalUser("helpdesk", HERS, "the first password")

        resets.request(DIRECTORY, CALLER)
        /*
         * A second request, for somebody who does get one, and then the assertion
         * that only one mail exists. Nothing can prove a mail is not on its way by
         * looking at an empty list, but the mails go out on one thread in the
         * order they were handed over: the second having arrived means the first
         * was already dealt with, so an empty place where it would have been is
         * the answer rather than a race.
         */
        resets.request(HERS, CALLER)
        await().atMost(Duration.ofSeconds(5)).until({ transport.sent.size }) { it == 1 }

        assertThat(transport.sent.single().second.to).containsExactly(HERS)
    }

    @Test
    fun `an internal identity that never had a password is not sent one`() {
        users.save(AppUser(username = "reporter", displayName = "Reporter", type = UserType.INTERNAL, email = NOBODYS))
        internalUser("helpdesk", HERS, "the first password")

        resets.request(NOBODYS, CALLER)
        resets.request(HERS, CALLER)
        await().atMost(Duration.ofSeconds(5)).until({ transport.sent.size }) { it == 1 }

        assertThat(transport.sent.single().second.to).containsExactly(HERS)
    }

    @Test
    fun `resetting a password ends the sessions the account had`() {
        internalUser("helpdesk", HERS, "the first password")
        val cookie = signIn("helpdesk", "the first password")
        assertThat(describeSession(cookie).statusCode).isEqualTo(HttpStatus.OK)

        resets.request(HERS, CALLER)
        resets.complete(tokenIn(mailed()), "a new long password", CALLER)

        assertThat(describeSession(cookie).statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `asking over and over is made to wait, and told for how long`() {
        internalUser("helpdesk", HERS, "the first password")

        repeat(3) { assertThat(ask(HERS).statusCode).isEqualTo(HttpStatus.OK) }
        val refused = ask(HERS)

        assertThat(refused.statusCode).isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
        /*
         * The wait, and not the sentence. Boot's error page leaves the reason out
         * unless an installation turns `server.error.include-message` on, which is
         * as true of the sign-in throttle beside this - so what a caller can rely
         * on is the status and this header.
         */
        assertThat(refused.headers.getFirst(HttpHeaders.RETRY_AFTER)?.toLongOrNull()).isNotNull().isPositive()
    }

    private fun internalUser(username: String, email: String, password: String): AppUser = users.save(
        AppUser(
            username = username,
            displayName = username,
            type = UserType.INTERNAL,
            email = email,
            passwordHash = encoder.encode(password),
        ),
    )

    /** The body of the one mail that went out, once it has. */
    private fun mailed(): String =
        await().atMost(Duration.ofSeconds(5)).until({ transport.sent.firstOrNull() }) { it != null }!!.second.body

    private fun tokenIn(body: String): String =
        requireNotNull(Regex("token=(\\S+)").find(body)) { "no link in: $body" }.groupValues[1]

    private fun ask(email: String): ResponseEntity<String> = client.post()
        .uri("/api/password-reset")
        .contentType(MediaType.APPLICATION_JSON)
        .body("""{"email":"$email"}""")
        .retrieve()
        .toEntity(String::class.java)

    private fun signIn(username: String, password: String): String {
        val response = client.post()
            .uri("/api/session")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"username":"$username","password":"$password"}""")
            .retrieve()
            .toEntity(String::class.java)

        return requireNotNull(
            response.headers[HttpHeaders.SET_COOKIE]?.firstOrNull { it.startsWith("SESSION=") }?.substringBefore(';'),
        ) { "no session cookie in $response" }
    }

    private fun describeSession(cookie: String): ResponseEntity<String> = client.get()
        .uri("/api/session")
        .header(HttpHeaders.COOKIE, cookie)
        .retrieve()
        .toEntity(String::class.java)

    private fun hashOf(secret: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(secret.toByteArray()))

    /**
     * Stands in for the relay, registered as primary so the real transport is
     * still built by the context it replaces - a bean that fails to construct is
     * something this suite should notice.
     */
    @TestConfiguration
    class FakeTransport {

        @Bean
        @Primary
        fun recordingMailTransport(): RecordingMailTransport = RecordingMailTransport()
    }

    private companion object {
        const val HERS = "helpdesk@orknux.example"
        const val NOBODYS = "nobody@orknux.example"
        const val DIRECTORY = "alice@orknux.example"

        /** Every request in these tests arrives from the same place, as it would in life. */
        const val CALLER = "127.0.0.1"
    }
}
