package io.mszymanski.orknux.workflow.script

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

/** What bounds a script: how long it may run, and how much of it may run. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ScriptProperties::class)
class ScriptConfig
