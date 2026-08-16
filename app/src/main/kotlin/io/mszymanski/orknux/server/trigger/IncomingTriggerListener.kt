package io.mszymanski.orknux.server.trigger

import io.mszymanski.orknux.connector.connection.IncomingAction
import io.mszymanski.orknux.connector.connection.IncomingEvent
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Starts the workflows that were waiting for an event.
 *
 * The connection module publishes what arrived and knows nothing of triggers;
 * this is the other half of that arrangement. A trigger that has been disabled,
 * or that watches a different event on the same connection, is passed over — the
 * query is the whole of the matching.
 *
 * Matching a definition is only half the answer: a definition is a catalogue
 * entry and starts nothing by itself. [TriggerRunner] finds the workflows whose
 * trigger nodes instance it, which is where the wiring is done.
 */
@Component
class IncomingTriggerListener(
    private val triggers: WorkflowTriggerRepository,
    private val runs: TriggerRunner,
) {

    @EventListener
    fun onIncomingEvent(event: IncomingEvent) {
        val action = event.action.asTriggerAction()
        val waiting = triggers.findByConnectionIdAndActionAndEnabledTrue(event.connectionId, action)
        if (waiting.isEmpty()) {
            log.debug("A {} on connection {} matched no trigger", action, event.connectionId)
            return
        }

        val context = buildMap<String, String?> {
            put("action", action.name)
            put("text", event.text)
            putAll(event.context)
        }

        for (trigger in waiting) {
            // The trigger names a workspace; the event names the workspace the connection
            // belongs to. They can only differ if one of the two was moved
            // underneath the other, and then the trigger is stale.
            if (trigger.workspaceId != event.workspaceId) {
                log.warn("Trigger {} no longer belongs to the workspace its connection does", trigger.id)
                continue
            }
            runs.fire(trigger, context)
        }
    }

    /**
     * The two enums are declared apart — the connection module cannot see this
     * one — and are matched by name. `IncomingTriggerListenerTest` holds them
     * together.
     */
    private fun IncomingAction.asTriggerAction(): TriggerAction = TriggerAction.valueOf(name)

    private companion object {
        val log = LoggerFactory.getLogger(IncomingTriggerListener::class.java)
    }
}
