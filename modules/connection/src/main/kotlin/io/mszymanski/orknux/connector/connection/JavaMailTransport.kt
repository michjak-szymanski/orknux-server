package io.mszymanski.orknux.connector.connection

import jakarta.mail.AuthenticationFailedException
import jakarta.mail.SendFailedException
import jakarta.mail.internet.AddressException
import jakarta.mail.internet.MimeMessage
import org.springframework.mail.MailAuthenticationException
import org.springframework.mail.MailParseException
import org.springframework.mail.MailSendException
import org.springframework.mail.javamail.JavaMailSenderImpl
import org.springframework.mail.javamail.MimeMessageHelper
import org.springframework.stereotype.Component
import java.util.Properties

/**
 * Sending mail with Spring's JavaMailSender, one server at a time.
 *
 * A sender is built per send rather than injected, because the SMTP details
 * belong to a workspace's connection: there is no one mail server this
 * deployment sends through, and Boot's auto-configured sender would be exactly
 * that. No `spring.mail.*` property is set for the same reason, so the starter
 * is here for jakarta.mail and [MimeMessageHelper] and configures nothing of its
 * own. Building a session costs nothing measurable next to opening the socket.
 *
 * The alternative was talking SMTP over a plain socket, which is a protocol
 * nobody should implement twice - MIME encoding, folded headers, dot-stuffing,
 * STARTTLS - to save one dependency the framework already manages the version of.
 */
@Component
class JavaMailTransport(private val properties: ConnectionProperties) : MailTransport {

    override fun deliver(server: SmtpServer, message: MailMessage): MailDelivery {
        val sender = senderFor(server)
        val mime: MimeMessage = sender.createMimeMessage()

        return try {
            // Not multipart: the body is text, and a multipart wrapper around a
            // single text part is a structure with nothing in the other half.
            val helper = MimeMessageHelper(mime, false, Charsets.UTF_8.name())
            helper.setFrom(server.from)
            helper.setTo(message.to.toTypedArray())
            if (message.cc.isNotEmpty()) helper.setCc(message.cc.toTypedArray())
            message.replyTo?.let(helper::setReplyTo)
            helper.setSubject(message.subject)
            helper.setText(message.body, false)

            sender.send(mime)
            // Set by the session as the message is sent, and the only handle a
            // mail server's own log can be searched by afterwards.
            MailDelivery.Sent(message.to, runCatching { mime.messageID }.getOrNull())
        } catch (failure: Exception) {
            MailDelivery.Refused(reasonFor(failure), permanent = permanent(failure))
        }
    }

    override fun check(server: SmtpServer): CheckResult = try {
        // Connects, negotiates and authenticates, then closes without sending -
        // which is the whole question the check button asks, and the only way to
        // ask it that does not put a mail in somebody's inbox.
        senderFor(server).testConnection()
        CheckResult(CheckOutcome.CONNECTED, "Connected to ${server.host}:${server.port}")
    } catch (failure: Exception) {
        CheckResult(CheckOutcome.FAILED, reasonFor(failure).replaceFirstChar(Char::uppercase))
    }

    private fun senderFor(server: SmtpServer): JavaMailSenderImpl = JavaMailSenderImpl().apply {
        host = server.host
        port = server.port
        server.username?.let { username = it }
        server.password?.let { password = it }
        defaultEncoding = Charsets.UTF_8.name()
        javaMailProperties = sessionProperties(server)
    }

    private fun sessionProperties(server: SmtpServer): Properties = Properties().apply {
        this["mail.transport.protocol"] = "smtp"
        // Only offer credentials when there are credentials: a relay that
        // authenticates nobody rejects the AUTH command rather than ignoring it.
        this["mail.smtp.auth"] = (server.username != null).toString()

        when (server.security) {
            /*
             * Required, not merely enabled. Enabled alone means "upgrade if the
             * server offers it", so a server that has stopped offering TLS - or
             * something answering in its place - would quietly receive the
             * password in the clear on a connection somebody configured as
             * encrypted.
             */
            MailSecurity.STARTTLS -> {
                this["mail.smtp.starttls.enable"] = "true"
                this["mail.smtp.starttls.required"] = "true"
            }

            MailSecurity.TLS -> this["mail.smtp.ssl.enable"] = "true"
            MailSecurity.NONE -> this["mail.smtp.starttls.enable"] = "false"
        }

        // All three, because they fail differently: a server that never answers
        // the connect, one that stops answering mid-conversation, and one that
        // stops reading while the body is being written. Without them a run holds
        // its step until whatever is carrying it gives up instead.
        val timeout = (properties.mailTimeoutSeconds * 1000).toString()
        this["mail.smtp.connectiontimeout"] = timeout
        this["mail.smtp.timeout"] = timeout
        this["mail.smtp.writetimeout"] = timeout
    }

    /**
     * Whether asking again could give a different answer.
     *
     * Decided on what was thrown rather than on the SMTP reply code: the code is
     * only reachable through the mail implementation's own exception classes,
     * which the starter is free to change underneath this, and the two answers
     * worth separating both have an exception of their own. A password the server
     * refused and an address it will not accept are settled; everything else -
     * connection refused, a timeout, a relay having a bad afternoon - is the kind
     * of thing a retry exists for.
     */
    private fun permanent(failure: Throwable): Boolean = when (failure) {
        is MailAuthenticationException, is AuthenticationFailedException -> true
        // The message could not be built from what the action said, so no server
        // will take it until the action is edited. An address that is not an
        // address is the common one, and it is worth being final about: a node
        // wired to the wrong field would otherwise be retried for it.
        is MailParseException, is AddressException, is IllegalArgumentException -> true
        is SendFailedException -> failure.invalidAddresses?.isNotEmpty() == true
        is MailSendException -> failure.failedMessages.values.any { permanent(it) } ||
            failure.cause?.let(::permanent) == true

        else -> false
    }

    /** What went wrong, in the words the mail server or the library used. */
    private fun reasonFor(failure: Throwable): String {
        val cause = when (failure) {
            // Spring's own message is a count of failed messages; the exception
            // underneath is the one carrying the server's reply.
            is MailSendException -> failure.failedMessages.values.firstOrNull() ?: failure.cause ?: failure
            else -> failure
        }
        return cause.message?.trim()?.ifEmpty { null } ?: "the mail server refused the message"
    }
}
