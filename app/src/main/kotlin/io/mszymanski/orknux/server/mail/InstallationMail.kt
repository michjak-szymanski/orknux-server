package io.mszymanski.orknux.server.mail

import io.mszymanski.orknux.connector.connection.MailDelivery
import io.mszymanski.orknux.connector.connection.MailMessage
import io.mszymanski.orknux.connector.connection.MailSecurity
import io.mszymanski.orknux.connector.connection.MailTransport
import io.mszymanski.orknux.connector.connection.SmtpServer
import io.mszymanski.orknux.connector.connection.defaultPort
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.stereotype.Component

/**
 * The mail server this installation sends its own mail through.
 *
 * Not a workspace's. Mail already worked before this, but only as a workspace's
 * SMTP connection - a credential one team pasted into their own settings so
 * their workflows could send from their own address. A password reset is not
 * that: it is the installation writing to somebody about their account, it has
 * to work for a person who belongs to no workspace at all, and borrowing a
 * team's relay would mean the reset mail stopped the day that team rotated their
 * password, went out over an address the account has nothing to do with, and put
 * a credential a workspace administrator can read in the path of getting into
 * anybody's account.
 *
 * So it is configuration rather than a stored connection: an operator's setting,
 * beside the database URL and the LDAP bind, encrypted by nothing here because
 * it is never in the database. Empty means this installation cannot send its own
 * mail, which is a supported state - it simply cannot offer a password reset,
 * and says so in the log rather than falling back to somebody else's server.
 */
@ConfigurationProperties(prefix = "orknux.mail")
data class InstallationMailProperties(
    /** The relay's host name. Empty means this installation does not send mail. */
    val host: String = "",

    /** Null takes whatever [security] usually listens on. */
    val port: Int? = null,

    /** Empty sends without authenticating, which is what an internal relay usually wants. */
    val username: String = "",

    val password: String = "",

    /** What the mail is from. Required, since a relay will not take a message without one. */
    val from: String = "",

    val security: MailSecurity = MailSecurity.STARTTLS,
)

/**
 * Sending one mail as the installation, through the transport the connection
 * module already owns.
 *
 * Reusing [MailTransport] rather than writing a second sender: how a session is
 * secured, which failures are worth retrying and what a refusal reads like are
 * decided once, and a test can stand in front of it without a mail server - which
 * is what lets the password reset be exercised without putting a message in
 * anybody's inbox.
 */
@Component
@EnableConfigurationProperties(InstallationMailProperties::class)
class InstallationMail(
    private val properties: InstallationMailProperties,
    private val transport: MailTransport,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Whether there is a server to send through at all. */
    val configured: Boolean
        get() = server() != null

    fun send(message: MailMessage): MailDelivery {
        val server = server()
            ?: return MailDelivery.NotPossible("this installation has no mail server configured")

        return try {
            transport.deliver(server, message)
        } catch (failure: Exception) {
            /*
             * The transport answers with a delivery for everything it expects;
             * this is the one it did not. Swallowed into a refusal because the
             * caller is usually a background thread with nobody to report to,
             * and a thrown exception there would only be a stack trace with no
             * context about which mail it was.
             */
            log.warn("Could not send installation mail to {}", message.to, failure)
            MailDelivery.Refused(failure.message ?: "the mail server could not be reached", permanent = false)
        }
    }

    private fun server(): SmtpServer? {
        val host = properties.host.trim().ifEmpty { return null }
        val from = properties.from.trim().ifEmpty { return null }
        val user = properties.username.trim().ifEmpty { null }

        return SmtpServer(
            host = host,
            port = properties.port ?: properties.security.defaultPort,
            username = user,
            // Only meaningful with a user to go with it, the same rule a
            // workspace's connection applies to the same pair of fields.
            password = user?.let { properties.password.trim().ifEmpty { null } },
            from = from,
            security = properties.security,
        )
    }
}
