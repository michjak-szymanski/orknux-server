package io.mszymanski.gyloli.server.workflow

import io.mszymanski.gyloli.server.action.ActionParamView
import io.mszymanski.gyloli.server.action.ActionParameters
import io.mszymanski.gyloli.server.action.ValueType
import io.mszymanski.gyloli.server.action.WorkflowActionRepository
import io.mszymanski.gyloli.server.condition.ConditionProperty
import io.mszymanski.gyloli.server.condition.ConditionType
import io.mszymanski.gyloli.server.condition.WorkflowCondition
import io.mszymanski.gyloli.server.condition.WorkflowConditionRepository
import io.mszymanski.gyloli.server.trigger.TriggerType
import io.mszymanski.gyloli.server.trigger.WorkflowTrigger
import io.mszymanski.gyloli.server.trigger.WorkflowTriggerRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper

/**
 * Whether a graph holds together.
 *
 * The rules are generic: every node reports what it needs and what it produces —
 * its **ports** — and an edge is sound when what flows into a node covers what
 * that node needs. Nothing about a particular kind is written into the rules;
 * the kinds differ only in how their ports are worked out, which is the point of
 * [portsOf].
 *
 * The ports are derived rather than stored. A node keeps the id of the catalogue
 * entry it uses and nothing else, so editing an action changes what its nodes
 * need immediately; a copy on the node would be a second truth that goes stale.
 *
 * Two rules are refusals and the rest are warnings. A graph is drawn before it
 * is finished, so a workflow that is not yet wired up has to be saveable — but a
 * shape that could never run, like something feeding a trigger, is not.
 */
@Service
class GraphValidator(
    private val triggers: WorkflowTriggerRepository,
    private val actions: WorkflowActionRepository,
    private val conditions: WorkflowConditionRepository,
    private val parameters: ActionParameters,
    private val mapper: ObjectMapper,
) {

    /** What a node needs, what it hands on, and whether its input survives it. */
    data class Ports(
        val inputs: List<ActionParamView> = emptyList(),
        val outputs: List<ActionParamView> = emptyList(),
        /**
         * True when the node hands on what it was given as well as its own
         * output — a wait and a condition do, work does not.
         */
        val passThrough: Boolean = false,
        /**
         * True when nothing is known about what the node produces, so anything
         * asking for a field downstream is given the benefit of the doubt.
         */
        val opaque: Boolean = false,
        /** Why the node cannot say, if it cannot: nothing chosen from a catalogue. */
        val unresolved: String? = null,
    )

    /** What a node needs and gives, worked out from what it points at. */
    fun portsOf(node: WorkflowNode): Ports = when (node.kind) {
        NodeKind.TRIGGER -> {
            val trigger = node.triggerId?.let { triggers.findByIdOrNull(it) }
            if (trigger == null) {
                Ports(opaque = true, unresolved = "no trigger chosen")
            } else {
                Ports(outputs = fieldsOf(trigger))
            }
        }

        NodeKind.ACTION -> {
            val action = node.actionId?.let { actions.findByIdOrNull(it) }
            if (action == null) {
                Ports(opaque = true, unresolved = "no action chosen")
            } else {
                Ports(
                    // Only what is still open: a setting the action already
                    // holds is not something an edge has to bring it.
                    inputs = parameters.requiredInputsOf(action),
                    outputs = parameters.outputsOf(action),
                    passThrough = parameters.passesThrough(action),
                )
            }
        }

        NodeKind.CONDITION -> {
            val condition = node.conditionId?.let { conditions.findByIdOrNull(it) }
            if (condition == null) {
                Ports(passThrough = true, opaque = true, unresolved = "no condition chosen")
            } else {
                // A condition hands on what it was given, unchanged, when it holds.
                Ports(inputs = asks(condition, depth = 0), passThrough = true)
            }
        }

        // Nothing runs these yet, so they claim nothing and require nothing.
        NodeKind.AGENT, NodeKind.DATA_TASK, NodeKind.PUBLISH_TASK -> Ports(passThrough = true, opaque = true)
    }

    /**
     * Everything wrong with the graph, worst first.
     *
     * @param hardOnly what a save refuses over; the rest is advice the editor
     *   shows while a workflow is still being drawn.
     */
    fun problems(
        nodes: List<WorkflowNode>,
        edges: List<WorkflowEdge>,
        hardOnly: Boolean = false,
    ): List<GraphProblem> {
        val byKey = nodes.associateBy { it.nodeKey }
        val known = edges.filter { it.sourceKey in byKey && it.targetKey in byKey }
        val problems = mutableListOf<GraphProblem>()

        // --- The two shapes that could never run ---
        known.forEach { edge ->
            val target = byKey.getValue(edge.targetKey)
            val source = byKey.getValue(edge.sourceKey)
            if (target.kind == NodeKind.TRIGGER) {
                problems += GraphProblem(
                    severity = GraphProblemSeverity.ERROR,
                    nodeKey = target.nodeKey,
                    message = "Nothing can feed ${target.name}: a trigger is where a run starts.",
                )
            }
            if (source.kind == NodeKind.PUBLISH_TASK) {
                problems += GraphProblem(
                    severity = GraphProblemSeverity.ERROR,
                    nodeKey = source.nodeKey,
                    message = "Nothing can follow ${source.name}: a publish task is where a run ends.",
                )
            }
        }
        if (hardOnly) return problems

        // --- What each node can see, followed along the edges ---
        val ports = nodes.associate { it.nodeKey to portsOf(it) }
        val available = availability(nodes, known, ports)

        nodes.forEach { node ->
            val port = ports.getValue(node.nodeKey)
            port.unresolved?.let {
                problems += GraphProblem(
                    severity = GraphProblemSeverity.WARNING,
                    nodeKey = node.nodeKey,
                    message = "${node.name} has $it, so it will do nothing.",
                )
            }

            val incoming = known.count { it.targetKey == node.nodeKey }
            if (node.kind != NodeKind.TRIGGER && incoming == 0 && nodes.size > 1) {
                problems += GraphProblem(
                    severity = GraphProblemSeverity.WARNING,
                    nodeKey = node.nodeKey,
                    message = "${node.name} has nothing before it, so a run never reaches it.",
                )
            }

            val reachable = available.getValue(node.nodeKey)
            port.inputs.forEach { needed ->
                val seen = reachable.fields[needed.name]
                when {
                    // Something before it says nothing about what it produces,
                    // so this cannot be called wrong.
                    reachable.opaque -> Unit
                    seen == null -> problems += GraphProblem(
                        severity = GraphProblemSeverity.WARNING,
                        nodeKey = node.nodeKey,
                        message = "${node.name} needs ${needed.display}, which nothing before it produces.",
                    )

                    !compatible(seen, needed.type) -> problems += GraphProblem(
                        severity = GraphProblemSeverity.WARNING,
                        nodeKey = node.nodeKey,
                        message =
                            "${node.name} needs ${needed.display}, but what reaches it is " +
                                "${needed.name}: ${seen.name.lowercase()}.",
                    )
                }
            }
        }

        return problems.distinct().sortedBy { it.severity.ordinal }
    }

    /** What has reached each node, following the edges from the ones that start. */
    private fun availability(
        nodes: List<WorkflowNode>,
        edges: List<WorkflowEdge>,
        ports: Map<String, Ports>,
    ): Map<String, Reachable> {
        val byKey = nodes.associateBy { it.nodeKey }
        val order = runCatching { orderOf(nodes, edges) }.getOrDefault(nodes)
        val produced = mutableMapOf<String, Reachable>()
        val incoming = mutableMapOf<String, Reachable>()

        order.forEach { node ->
            val sources = edges.filter { it.targetKey == node.nodeKey }.mapNotNull { produced[it.sourceKey] }
            val reaching = sources.fold(Reachable()) { all, one -> all.merge(one) }
            incoming[node.nodeKey] = reaching

            val port = ports.getValue(node.nodeKey)
            val own = Reachable(
                fields = port.outputs.associate { it.name to it.type },
                opaque = port.opaque,
            )
            produced[node.nodeKey] = if (port.passThrough) reaching.merge(own) else own
        }

        return byKey.keys.associateWith { incoming[it] ?: Reachable() }
    }

    /** Nodes in the order a run would reach them; the graph's own order otherwise. */
    private fun orderOf(nodes: List<WorkflowNode>, edges: List<WorkflowEdge>): List<WorkflowNode> {
        val byKey = nodes.associateBy { it.nodeKey }
        val waiting = nodes.associate { it.nodeKey to edges.count { edge -> edge.targetKey == it.nodeKey } }
            .toMutableMap()
        val ready = ArrayDeque(nodes.filter { waiting.getValue(it.nodeKey) == 0 })
        val ordered = mutableListOf<WorkflowNode>()

        while (ready.isNotEmpty()) {
            val node = ready.removeFirst()
            ordered += node
            edges.filter { it.sourceKey == node.nodeKey }.forEach { edge ->
                val left = waiting.getValue(edge.targetKey) - 1
                waiting[edge.targetKey] = left
                if (left == 0) ready += byKey.getValue(edge.targetKey)
            }
        }
        // A cycle leaves nodes behind; they are checked in the order they were drawn.
        return ordered + nodes.filterNot { node -> ordered.any { it.nodeKey == node.nodeKey } }
    }

    /** What a trigger puts in the run's input: its payload, and what fired it. */
    private fun fieldsOf(trigger: WorkflowTrigger): List<ActionParamView> {
        val fromPayload = trigger.payload
            ?.let { runCatching { mapper.readTree(it) }.getOrNull() }
            ?.takeIf { it.isObject }
            ?.properties()
            ?.map { (name, value) ->
                ActionParamView(
                    name,
                    when {
                        value.isNumber -> ValueType.NUMBER
                        value.isBoolean -> ValueType.BOOLEAN
                        value.isArray -> ValueType.ARRAY
                        value.isObject -> ValueType.OBJECT
                        else -> ValueType.STRING
                    },
                )
            }
            .orEmpty()

        val fromFiring = when (trigger.type) {
            TriggerType.SCHEDULED -> listOf("cron", "firedAt")
            TriggerType.INCOMING_CONNECTION -> listOf("action", "text", "channel", "user", "ts", "threadTs")
        }.map { ActionParamView(it, ValueType.STRING) }

        return (fromPayload + fromFiring).distinctBy { it.name }
    }

    /** Which fields a condition reads, so a node asking it needs those. */
    private fun asks(condition: WorkflowCondition, depth: Int): List<ActionParamView> {
        if (depth > MAX_DEPTH) return emptyList()
        return when (condition.type) {
            ConditionType.ANY_OF, ConditionType.ALL_OF -> condition.members
                .mapNotNull { conditions.findByIdOrNull(it) }
                .flatMap { asks(it, depth + 1) }
                .distinctBy { it.name }

            // The function is handed everything, so it asks for nothing by name.
            ConditionType.FUNCTION -> emptyList()
            ConditionType.TIME -> emptyList()
            else -> condition.property?.let { property ->
                fieldFor(property)?.let { listOf(ActionParamView(it, ValueType.STRING)) }
            }.orEmpty()
        }
    }

    /** The field each property is read from; `ConditionEvaluator` reads the same. */
    private fun fieldFor(property: ConditionProperty): String? = when (property) {
        ConditionProperty.MESSAGE_AUTHOR -> "user"
        ConditionProperty.MESSAGE_CHANNEL -> "channel"
        ConditionProperty.MESSAGE_TEXT -> "text"
        ConditionProperty.ISSUE_PRIORITY -> "priority"
        ConditionProperty.ISSUE_STATUS -> "status"
        ConditionProperty.ISSUE_TYPE -> "issueType"
        ConditionProperty.CURRENT_TIME -> null
    }

    /**
     * Whether what arrives will do for what is wanted.
     *
     * An object is anything, and a string is what a placeholder becomes, so
     * those go anywhere; the rest have to agree.
     */
    private fun compatible(given: ValueType, wanted: ValueType): Boolean =
        given == wanted || given == ValueType.OBJECT || wanted == ValueType.OBJECT || wanted == ValueType.STRING

    /** What has reached a node: the fields, and whether anything is unknown. */
    private data class Reachable(
        val fields: Map<String, ValueType> = emptyMap(),
        val opaque: Boolean = false,
    ) {
        fun merge(other: Reachable) = Reachable(fields + other.fields, opaque || other.opaque)
    }

    private companion object {
        const val MAX_DEPTH = 10
    }
}

enum class GraphProblemSeverity {
    /** The graph could not run in this shape; the save is refused. */
    ERROR,

    /** Worth fixing before it runs, but a graph is drawn before it is finished. */
    WARNING,
}

data class GraphProblem(
    val severity: GraphProblemSeverity,
    /** The node it is about; edges are reported against the node they reach. */
    val nodeKey: String,
    val message: String,
)

class GraphInvalidException(problems: List<GraphProblem>) :
    RuntimeException(problems.joinToString(" ") { it.message })
