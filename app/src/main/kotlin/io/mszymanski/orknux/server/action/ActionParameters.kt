package io.mszymanski.orknux.server.action

import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service

/**
 * What an action needs and what it produces.
 *
 * These are read off the action's settings rather than stored — a
 * `{{input.name}}` typed into the content is an input the moment it is typed —
 * so this is the one place that rule lives. Both the action API, which shows
 * them on the form, and the graph validator, which checks an edge can satisfy
 * them, ask here.
 */
@Service
class ActionParameters(private val functions: WorkflowFunctionRepository) {

    /**
     * What the action has to be given.
     *
     * A `{{input.x}}` anywhere in the settings is one, and so is an `input.x`
     * named by a wait's expression — those are the two ways an action refers to
     * something it does not have yet.
     */
    fun inputsOf(action: WorkflowAction): List<ActionParamView> = when (action.subtype) {
        ActionSubtype.OUTGOING_CONNECTION ->
            placeholders(action.content, action.targetName).map { ActionParamView(it, ValueType.STRING) }

        ActionSubtype.HTTP_REQUEST ->
            placeholders(action.url, action.headers).map { ActionParamView(it, ValueType.STRING) }

        ActionSubtype.FUNCTION ->
            placeholders(*action.mappings.map { it.expression }.toTypedArray())
                .map { ActionParamView(it, ValueType.STRING) }

        ActionSubtype.INLINE_CONDITION ->
            references(action.conditionExpression).map { ActionParamView(it, ValueType.BOOLEAN) }

        // What a saved condition needs is the condition's business, not the
        // action's; the action needs whatever the run is already carrying.
        ActionSubtype.CONDITION -> emptyList()

        ActionSubtype.TIME -> listOf(ActionParamView("duration", ValueType.NUMBER))
    }

    /**
     * What the action still has to be given by whatever comes before it.
     *
     * A delay's duration is one of its inputs on the form, because that is what
     * the action is about — but it is answered by the action's own settings, so
     * nothing upstream has to produce it. Only what is still open is what a
     * graph can be wrong about.
     */
    fun requiredInputsOf(action: WorkflowAction): List<ActionParamView> = when (action.subtype) {
        ActionSubtype.TIME -> emptyList()
        else -> inputsOf(action)
    }

    /** What the next node is handed, which follows from what the action did. */
    fun outputsOf(action: WorkflowAction): List<ActionParamView> = when (action.subtype) {
        ActionSubtype.OUTGOING_CONNECTION -> listOf(ActionParamView("status", ValueType.BOOLEAN))
        ActionSubtype.HTTP_REQUEST -> listOf(ActionParamView("response", ValueType.OBJECT))
        ActionSubtype.FUNCTION -> listOf(
            ActionParamView(
                "result",
                action.functionId?.let { functions.findByIdOrNull(it) }?.returnType ?: ValueType.OBJECT,
            ),
        )

        // A wait answers with what it was waiting on.
        ActionSubtype.INLINE_CONDITION ->
            references(action.conditionExpression).map { ActionParamView(it, ValueType.BOOLEAN) }

        ActionSubtype.CONDITION -> listOf(ActionParamView("held", ValueType.BOOLEAN))
        ActionSubtype.TIME -> emptyList()
    }

    /**
     * The parameters a node pointing at this action has to supply, and what the
     * action itself suggests for each.
     *
     * Only what applies to this subtype: a function's arguments, the
     * placeholders an HTTP call or a message actually refers to, a wait's
     * duration. A node panel that listed every parameter any action could have
     * would be asking about things this one has no use for.
     *
     * The expression beside each is a starting point taken from the action —
     * the seed a node copies once. After that the node owns them, and this is
     * not consulted again.
     */
    fun defaultsFor(action: WorkflowAction): List<ActionParamDefault> = when (action.subtype) {
        // The function decides the arguments; the action may already suggest an
        // expression for some of them.
        ActionSubtype.FUNCTION -> {
            val suggested = action.mappings.associate { it.argument to it.expression }
            val declared = action.functionId?.let { functions.findByIdOrNull(it) }?.params.orEmpty()
            declared.map { param ->
                ActionParamDefault(
                    name = param.name,
                    type = param.type,
                    // Nothing suggested means take it from upstream by name,
                    // which is the mapping somebody would write by hand.
                    expression = suggested[param.name] ?: "{{input.${param.name}}}",
                )
            }
        }

        ActionSubtype.OUTGOING_CONNECTION ->
            placeholders(action.content, action.targetName).map { defaultReference(it, ValueType.STRING) }

        ActionSubtype.HTTP_REQUEST ->
            placeholders(action.url, action.headers).map { defaultReference(it, ValueType.STRING) }

        ActionSubtype.INLINE_CONDITION ->
            references(action.conditionExpression).map { defaultReference(it, ValueType.BOOLEAN) }

        // A delay is told how long by its own settings, so the node supplies
        // nothing; a saved condition needs what the run already carries.
        ActionSubtype.TIME, ActionSubtype.CONDITION -> emptyList()
    }

    private fun defaultReference(name: String, type: ValueType) =
        ActionParamDefault(name = name, type = type, expression = "{{input.$name}}")

    /**
     * What a node still needs from upstream, given what it was told to pass.
     *
     * A parameter answered with a literal is answered: only the ones whose
     * expressions still refer to `input.x` have to be produced by something
     * before it. This is why a node bound to a fixed value validates without an
     * edge that carries it.
     */
    fun requiredInputsOf(mappings: List<Pair<String, String>>): List<ActionParamView> = mappings
        .flatMap { (_, expression) -> placeholders(expression) + references(expression) }
        .distinct()
        .map { ActionParamView(it, ValueType.STRING) }

    /**
     * Whether the run's input survives the action.
     *
     * A wait hands on what it was given, so everything before it stays
     * available; something that performs work answers with its own output and
     * that is all the next node sees.
     */
    fun passesThrough(action: WorkflowAction): Boolean = action.type == ActionType.WAIT

    /** Every `{{input.x}}` in the given settings, in the order they appear. */
    private fun placeholders(vararg settings: String?): List<String> = settings
        .filterNotNull()
        .flatMap { PLACEHOLDER.findAll(it).map { match -> match.groupValues[1] } }
        .distinct()

    /** Every `input.x` an expression reads. */
    private fun references(expression: String?): List<String> = expression
        ?.let { REFERENCE.findAll(it).map { match -> match.groupValues[1] }.toList() }
        .orEmpty()
        .distinct()

    private companion object {
        val PLACEHOLDER = Regex("""\{\{\s*input\.([A-Za-z_][A-Za-z0-9_]*)\s*}}""")
        val REFERENCE = Regex("""\binput\.([A-Za-z_][A-Za-z0-9_]*)""")
    }
}

/** A parameter of an action, and the expression the action suggests for it. */
data class ActionParamDefault(
    val name: String,
    val type: ValueType,
    val expression: String,
)
