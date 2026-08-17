package io.mszymanski.orknux.server.monitoring

import io.mszymanski.orknux.workflow.temporal.TemporalProperties
import io.mszymanski.orknux.workflow.temporal.temporalWorkflowId
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component

/**
 * Where to look at what Temporal did, when there is somewhere to look.
 *
 * A run's own page here says what each node did and what it said. What Temporal
 * has is the other half — every attempt, every timer, every retry — and that
 * screen already exists, so this offers the way to it rather than rebuilding it.
 *
 * Everything is optional on purpose. Temporal can be turned off, in which case
 * there is no properties bean at all; and it can be running without its web
 * interface exposed, in which case there is nothing to link to. Either way this
 * answers null and nothing is shown.
 */
@Component
class TemporalLinks(private val properties: ObjectProvider<TemporalProperties>) {

    /** The web interface itself, for someone who wants to go and look. */
    fun home(): String? = base()

    /** The one workflow that ran this execution. */
    fun forExecution(executionId: Long): String? {
        val held = properties.ifAvailable ?: return null
        val root = base() ?: return null
        return "$root/namespaces/${held.namespace}/workflows/${temporalWorkflowId(executionId)}"
    }

    private fun base(): String? = properties.ifAvailable
        ?.uiUrl
        ?.trim()
        ?.trimEnd('/')
        ?.ifEmpty { null }
}
