package io.mszymanski.orknux.server.trigger

import io.mszymanski.orknux.connector.connection.IncomingAction
import io.mszymanski.orknux.connector.connection.IncomingEvent
import io.mszymanski.orknux.connector.connection.SlackBotUsers
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
    /** Who each watched connection posts as; the answer is cached, never asked per reply. */
    private val botUsers: SlackBotUsers,
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
            if (!toOneOfOurs(trigger, event)) continue
            runs.fire(trigger, context)
        }
    }

    /**
     * Whether a reply hangs under a message one of the trigger's own bots wrote.
     *
     * **This is the whole of what a reply trigger means.** Every other kind of
     * trigger is matched by the query above and nothing else; this one has a
     * second question, because "a reply" on its own is every thread in every
     * channel the bot can read, which is not what anybody asks for.
     *
     * The answer is `parent_user_id` — Slack's own id for the author of the
     * message the thread hangs under — measured against the Slack user behind
     * each watched bot token, since a bot token *is* a Slack user. Resolving
     * that is a lookup in `SlackBotUsers`' cache and not a call to Slack.
     *
     * **A miss is passed over rather than recorded.** It is the same kind of
     * non-event as a mention arriving for a trigger watching another connection:
     * this reply was never this trigger's. Writing a firing for each would fill
     * the log a trigger's own page exists to make readable with every thread
     * anybody in the workspace happens to be having.
     */
    private fun toOneOfOurs(trigger: WorkflowTrigger, event: IncomingEvent): Boolean {
        if (trigger.action != TriggerAction.REPLY) return true

        val parent = event.context["parentUserId"]
        if (parent == null) {
            log.debug(
                "A reply on connection {} carried no parent, so trigger {} is not the one",
                event.connectionId,
                trigger.id,
            )
            return false
        }
        // Watching nobody is refused on save, so a definition here with an empty
        // list predates the guard or was written straight to the database. It
        // matches nothing, which is the safe half of the two.
        return parent in botUsers.userIdsOf(trigger.watchedConnectionIds)
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
