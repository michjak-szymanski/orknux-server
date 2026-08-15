package io.mszymanski.gyloli.server.action

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ActionProperties::class)
class ActionConfig
