package io.mszymanski.orknux.server.workflow

import io.mszymanski.orknux.server.action.ActionParamView
import io.mszymanski.orknux.server.action.ActionParameters
import io.mszymanski.orknux.server.action.ValueType
import io.mszymanski.orknux.server.action.WorkflowActionRepository
import io.mszymanski.orknux.server.condition.ConditionProperty
import io.mszymanski.orknux.server.condition.ConditionType
import io.mszymanski.orknux.server.condition.WorkflowCondition
import io.mszymanski.orknux.server.condition.WorkflowConditionRepository
import io.mszymanski.orknux.server.obj.PropertyKind
import io.mszymanski.orknux.server.obj.WorkflowObjectRepository
import io.mszymanski.orknux.server.trigger.TriggerType
import io.mszymanski.orknux.server.trigger.WorkflowTrigger
import io.mszymanski.orknux.server.trigger.WorkflowTriggerRepository
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
 * is finished, so a workflow that is not yet wired up has to be saveable — but
 * two nodes answering to one name, or something feeding a trigger, is not.
 */
@Service
class GraphValidator(
    private val triggers: WorkflowTriggerRepository,
    private val actions: WorkflowActionRepository,
    private val conditions: WorkflowConditionRepository,
    private val objects: WorkflowObjectRepository,
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
                val named = node.outputName?.trim().orEmpty()
                Ports(
                    // What the *node* still needs, which is what will actually
                    // run: a parameter answered with a value is answered, and
                    // nothing upstream has to produce it. Reading the action
                    // here instead would validate a binding the run never uses.
                    inputs = reads(node.mappings),
                    // A named node wraps what it produces under that name, so
                    // that is the field a later node can read. Unnamed, the
                    // action's own output stands — which is what it is called,
                    // not necessarily what arrives.
                    outputs = if (named.isEmpty()) {
                        parameters.outputsOf(action)
                    } else {
                        listOf(ActionParamView(named, parameters.outputsOf(action).firstOrNull()?.type ?: ValueType.OBJECT))
                    },
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

        /*
         * An agent answers with prose, so what it gives is only addressable once
         * the node has named it. Named, it declares that one field and stops
         * being opaque — which is what lets the editor show it and a later node
         * refer to it. Unnamed, it hands its answer on as before and nothing
         * downstream can say what it will contain.
         */
        NodeKind.AGENT -> {
            val named = node.outputName?.trim().orEmpty()
            if (named.isEmpty()) {
                Ports(passThrough = true, opaque = true)
            } else {
                Ports(outputs = listOf(ActionParamView(named, ValueType.STRING)))
            }
        }

        /*
         * An object node makes what its fields say it makes.
         *
         * Named, that is one field of that name holding the object, which is
         * what a later node points at. Unnamed, the fields themselves are what
         * goes on — an object put together and then handed over as its parts,
         * which is the shape the run was already carrying.
         *
         * It passes on what it was given as well: building something out of two
         * earlier steps should not throw away everything else they produced.
         */
        NodeKind.OBJECT -> {
            val named = node.outputName?.trim().orEmpty()
            val fields = shapeOf(node)
            Ports(
                inputs = reads(node.mappings),
                outputs = if (named.isEmpty()) fields else listOf(ActionParamView(named, ValueType.OBJECT)),
                passThrough = true,
            )
        }
    }

    /**
     * What an object node's fields are called, and what each holds.
     *
     * A saved shape says; a shape of the node's own is the parameters it holds,
     * which are text until something says otherwise.
     */
    private fun shapeOf(node: WorkflowNode): List<ActionParamView> {
        val saved = node.objectId?.let { objects.findByIdOrNull(it) }
        if (saved != null) {
            return saved.properties.map { ActionParamView(it.name, typeOf(it.kind)) }
        }
        return node.mappings.map { ActionParamView(it.name, ValueType.STRING) }
    }

    /** A property's shape, as the graph's own vocabulary of types. */
    private fun typeOf(kind: PropertyKind): ValueType = when (kind) {
        PropertyKind.STRING -> ValueType.STRING
        PropertyKind.NUMBER -> ValueType.NUMBER
        PropertyKind.BOOLEAN -> ValueType.BOOLEAN
        PropertyKind.OBJECT -> ValueType.OBJECT
        PropertyKind.ARRAY -> ValueType.ARRAY
    }

    /**
     * The fields a node's parameters read, which is what has to reach it.
     *
     * Only the references: a parameter holding a value is answered by the value.
     * A reference to `trigger.x` reads the event that started the run, which is
     * there however the node was reached, so nothing upstream has to produce it.
     * Of `response.status` only `response` is asked for — the field is what an
     * edge carries; the rest is inside it.
     */
    private fun reads(mappings: List<NodeMapping>): List<ActionParamView> = mappings
        .filter { it.mode == MappingMode.REFERENCE }
        .map { it.expression.trim().substringBefore('.') }
        .filter { it.isNotEmpty() && it != TRIGGER }
        .distinct()
        .map { ActionParamView(it, ValueType.STRING) }

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

        /*
         * Two nodes cannot answer to the same name.
         *
         * A run carries what every step produced, each under the name its node
         * gave it. Two nodes claiming one name means the later one quietly wins
         * and every reference to the first reads the wrong value — a workflow
         * that runs, finishes, and is wrong. Refused rather than warned about,
         * because there is no version of it that was intended.
         */
        nodes.filter { !it.outputName.isNullOrBlank() }
            .groupBy { requireNotNull(it.outputName) }
            .filterValues { it.size > 1 }
            .forEach { (name, claiming) ->
                claiming.forEach { node ->
                    problems += GraphProblem(
                        severity = GraphProblemSeverity.ERROR,
                        nodeKey = node.nodeKey,
                        message = "\"$name\" is produced by ${claiming.size} nodes; a name has to say which one",
                    )
                }
            }

        // --- The shape that could never run ---
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
            // A webhook's fields are whatever its contract says, below.
            TriggerType.WEBHOOK -> emptyList()
        }.map { ActionParamView(it, ValueType.STRING) }

        /*
         * What a webhook hands on is the shape it refuses anything else for.
         *
         * The endpoint answers 404 to a request that does not match, so by the
         * time a run exists every one of these fields is there — which is what
         * makes them worth offering as something to point a reference at.
         */
        val fromContract = trigger.objectId
            ?.let { objects.findByIdOrNull(it) }
            ?.properties
            ?.map { ActionParamView(it.name, typeOf(it.kind)) }
            .orEmpty()

        return (fromPayload + fromFiring + fromContract).distinctBy { it.name }
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
     * An object is anything, and anything can be read as text, so those go
     * anywhere; the rest have to agree.
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

        /** What a reference reads from when it does not read the run's payload. */
        const val TRIGGER = "trigger"
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
