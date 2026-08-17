package io.mszymanski.orknux.server.logging

import org.springframework.boot.SpringApplication
import org.springframework.boot.env.EnvironmentPostProcessor
import org.springframework.core.Ordered
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.MapPropertySource

/**
 * Turns the logging options this application offers into the ones Spring
 * understands.
 *
 * The choices worth making are: does anything go to a file, and is a line meant
 * for a person reading a terminal or for a collector parsing it. Spring can do
 * both, but spells the second one `logging.structured.format.console=ecs`, which
 * is a thing to look up rather than a thing to decide. So the configuration
 * reads:
 *
 * ```yaml
 * orknux:
 *   logging:
 *     file: /var/log/orknux/orknux.log   # absent means console only
 *     format: json                       # or plain
 * ```
 *
 * This has to be an [EnvironmentPostProcessor] rather than a bean: the logging
 * system is configured before the application context exists, so anything that
 * waits for beans has already missed its chance to decide where logs go.
 *
 * The translated values are added as the lowest-precedence property source, so
 * setting `logging.file.name` directly still wins. This offers a shorthand; it
 * does not take the long form away.
 */
class LoggingOptions : EnvironmentPostProcessor, Ordered {

    /**
     * After the config files have been read, since that is where these
     * properties are, and before the logging system starts, which every
     * [EnvironmentPostProcessor] is.
     */
    override fun getOrder(): Int = Ordered.LOWEST_PRECEDENCE

    override fun postProcessEnvironment(environment: ConfigurableEnvironment, application: SpringApplication) {
        val translated = mutableMapOf<String, Any>()

        val file = environment.getProperty("orknux.logging.file").orEmpty().trim()
        if (file.isNotEmpty()) {
            translated["logging.file.name"] = file
        }

        when (val format = environment.getProperty("orknux.logging.format", "plain").trim().lowercase()) {
            "plain" -> Unit

            // ECS is the JSON Spring writes: one object per line, with the
            // fields a collector expects already named as it expects them.
            "json" -> {
                translated["logging.structured.format.console"] = ECS
                translated["logging.structured.format.file"] = ECS
            }

            else -> throw IllegalArgumentException(
                "orknux.logging.format is '$format'; it has to be 'plain' or 'json'",
            )
        }

        if (translated.isNotEmpty()) {
            environment.propertySources.addLast(MapPropertySource(SOURCE_NAME, translated))
        }
    }

    private companion object {
        const val ECS = "ecs"
        const val SOURCE_NAME = "orknuxLoggingOptions"
    }
}
