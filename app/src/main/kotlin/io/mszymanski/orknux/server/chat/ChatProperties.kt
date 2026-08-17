package io.mszymanski.orknux.server.chat

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Whether this installation has a chat.
 *
 * A property of the deployment rather than of anybody using it: an installation
 * that exists to run workflows has no use for a chat window, and one whose
 * models are not cleared for open conversation should not be offering one.
 * Administrators can turn it off from the screen — that choice is stored and
 * wins — but false here is final, the same floor attachments sit on.
 */
@ConfigurationProperties(prefix = "orknux.chat")
data class ChatProperties(
    val enabled: Boolean = true,
)
