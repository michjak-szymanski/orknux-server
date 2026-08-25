package io.mszymanski.orknux.server.trigger

import io.mszymanski.orknux.connector.connection.IncomingAction
import io.mszymanski.orknux.connector.connection.IncomingEvent
import io.mszymanski.orknux.connector.connection.ConnectionType
import io.mszymanski.orknux.connector.connection.SlackBotUsers
import io.mszymanski.orknux.connector.connection.WorkspaceConnectionRepository
import org.springframework.data.domain.Sort
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
    /** Only to ask whether a trigger has ever fired. See [FiringOutcome.NOT_WATCHED]. */
    private val firings: TriggerFiringRepository,
    /** To find the other rows that are the same Slack app. See [deliveredTo]. */
    private val connections: WorkspaceConnectionRepository,
) {

    @EventListener
    fun onIncomingEvent(event: IncomingEvent) {
        val action = event.action.asTriggerAction()
        val waiting = triggers.findByConnectionIdInAndActionAndEnabledTrue(deliveredTo(event), action)
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
            /*
             * One at a time, and one failing does not take the rest with it.
             *
             * Two triggers on one event are two separate decisions somebody
             * made, and the second has done nothing wrong. Without this a
             * condition that could not be evaluated, or a workflow whose start
             * threw, ended the loop - so which triggers ran depended on the
             * order the query happened to return them in, and nothing said so.
             * `fire` records what it did, so a failure here is one this could
             * not even write down: it is logged and the next trigger is asked.
             */
            runCatching { runs.fire(trigger, context) }
                .onFailure { log.error("Trigger {} could not be fired", trigger.name, it) }
        }
    }

    /**
     * Every connection an event could have been meant for, not only the one it
     * arrived on.
     *
     * **One Slack app is often several connection rows.** A workspace may hold
     * a row for sending and a row for listening, and the same app can be added
     * to more than one workspace; each row with an app-level token opens its own
     * Socket Mode connection. Slack load-balances an app's events across its
     * open sockets and delivers each one to exactly *one* of them, of its own
     * choosing - so the connection id stamped on an arriving message is a
     * lottery between rows that are, to Slack, the same app.
     *
     * Matching on that id alone meant a trigger fired on some fraction of the
     * messages it was set up for and was silent on the rest, with nothing
     * anywhere to say why. This asks the question Slack is actually answering:
     * which rows *are* this bot? The comparison is the Slack user id behind each
     * bot token, which is what `SlackBotUsers` already caches for the reply
     * guard, so the widening costs a lookup and no call.
     *
     * **The workspace is still the boundary.** Only rows in the workspace the
     * event was delivered to are considered, so the same Slack app added to two
     * workspaces does not let a trigger in one hear traffic recorded against
     * the other - a workspace is who may see what, and Slack's routing is not a
     * reason to widen that.
     *
     * Falls back to the single connection whenever the bot behind it cannot be
     * resolved, which is the behaviour this replaces.
     *
     * **What a dead row costs.** Asking about every Slack row in the workspace
     * means asking about the ones whose token Slack refuses, and a refusal is
     * cached for thirty seconds rather than the full period a good answer is.
     * So a workspace carrying an abandoned connection pays one `auth.test` per
     * thirty seconds however busy the channel is - bounded, and the same shape
     * the reply guard already had. It is not per message, which is the thing
     * that would matter.
     */
    private fun deliveredTo(event: IncomingEvent): Set<Long> {
        val arrived = setOf(event.connectionId)
        return runCatching {
            val bot = botUsers.identify(event.connectionId).userId ?: return arrived
            val here = connections.findByWorkspaceId(event.workspaceId, Sort.unsorted())
                .filter { it.type == ConnectionType.SLACK }
                .mapNotNull { it.id }
            arrived + botUsers.identify(here).filter { it.userId == bot }.map { it.connectionId }
        }.getOrElse { arrived }
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
     * **A miss is passed over rather than recorded — except the first.** Every
     * one after it is the same kind of non-event as a mention arriving for a
     * trigger watching another connection: this reply was never this trigger's,
     * and writing a firing for each would fill the log a trigger's own page
     * exists to make readable with every thread anybody in the workspace happens
     * to be having.
     *
     * But the first is not that. A trigger with nothing to its name is one
     * somebody is still setting up, and "no firings at all" is the same picture
     * whether Slack is delivering nothing or delivering replies under the wrong
     * message — which is issue #269, reported as "does not trigger" with the
     * bot resolved, the scope granted and the trigger enabled. One line ends
     * that: it says the wire works and what arrived was not what was asked for.
     * After it, the trigger has a history and the silence is legible again.
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
            firstSignOfLife(trigger, "A reply arrived here, but not inside a thread one of the watched bots started")
            return false
        }
        // Watching nobody is refused on save, so a definition here with an empty
        // list predates the guard or was written straight to the database. It
        // matches nothing, which is the safe half of the two.
        if (parent in botUsers.userIdsOf(trigger.watchedConnectionIds)) return true

        firstSignOfLife(
            trigger,
            "A reply arrived here, under a message none of the watched bots wrote. " +
                "A reply trigger fires on replies to its own bots' messages, so the thread has to start with one.",
        )
        return false
    }

    /**
     * The one line a trigger that has never fired gets, and no more.
     *
     * The count is asked before the write, so a trigger already carrying a
     * history stays quiet — which is what keeps this from becoming a row per
     * thread. Never allowed to be the reason anything fails: this is a note
     * about an event that was not this trigger's in the first place.
     */
    private fun firstSignOfLife(trigger: WorkflowTrigger, detail: String) {
        runCatching {
            if (!firings.existsByTriggerId(requireNotNull(trigger.id))) {
                runs.note(trigger, FiringOutcome.NOT_WATCHED, detail)
            }
        }.onFailure { log.warn("Could not note what reached trigger {}", trigger.id, it) }
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
