package io.mszymanski.gyloli.server.action

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
