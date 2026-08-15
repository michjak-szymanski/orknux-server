package io.mszymanski.gyloli.connector.connection

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ConnectionProperties::class, SlackProperties::class)
class ConnectionConfig

/** What the Slack listener does, when there is a token for it to do it with. */
@ConfigurationProperties(prefix = "gyloli.slack")
data class SlackProperties(
    /**
     * False opens no sockets at all, whatever the connections hold. The tests
     * run that way; a deployment that wants to receive mentions should not.
     */
    val enabled: Boolean = true,

    /** How often the open sockets are compared with the stored connections. */
    val reconcileSeconds: Long = 30,

    /**
     * How long a connection that would not open is left alone. Slack answers
     * `invalid_auth` immediately, and asking again twice a minute for as long as
     * the process lives helps nobody; pasting a new token clears the wait.
     */
    val retryFailedSeconds: Long = 300,
)
