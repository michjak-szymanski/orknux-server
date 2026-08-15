package io.mszymanski.gyloli.server.security

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "gyloli.web")
data class WebProperties(
    /** Origins allowed to call the API with session cookies, e.g. the Vite dev server. */
    val allowedOrigins: List<String> = listOf("http://localhost:5173"),
)
