package io.mszymanski.orknux.workflow.script

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

/**
 * What bounds a script: how long it may run, and how much of it may run.
 *
 * Plugins are bounded separately, by their own properties, because they are
 * bigger and are loaded rather than called. The two sandboxes share nothing but
 * this line.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ScriptProperties::class, PluginProperties::class)
class ScriptConfig
