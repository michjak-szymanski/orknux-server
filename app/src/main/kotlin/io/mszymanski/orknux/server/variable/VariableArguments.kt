package io.mszymanski.orknux.server.variable

import io.mszymanski.orknux.server.action.WorkflowFunction
import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * What a function's external parameters come to, as arguments.
 *
 * Everything crossing into the sandbox is JSON, so this is where a variable
 * stops being the text a column holds and becomes the value the script sees: a
 * number arrives as a number, a boolean as a boolean, a string quoted.
 *
 * One place, because three callers need it — a node running a function, a
 * condition asking one, and a webhook checking who is calling — and a secret
 * decoded differently in three places is a secret that will eventually be logged
 * in one of them.
 */
@Component
class VariableArguments(
    private val variables: WorkspaceVariableRepository,
    private val mapper: ObjectMapper,
) {

    /**
     * The function's externals, in the order it receives them.
     *
     * A variable that has been deleted, or has no value yet, arrives as `null`
     * rather than stopping the call: a script checking a signature against
     * nothing should answer no, which is what it will do, and that is a better
     * failure than a run that dies before it can.
     */
    /**
     * @param instead values to hand over in place of what the workspace holds,
     *   by variable name and already JSON. Empty everywhere except a test run:
     *   see [FunctionCaller.call].
     */
    fun of(function: WorkflowFunction, instead: Map<String, String> = emptyMap()): List<String> =
        function.externals.map { external ->
            val variable = variables.findByIdOrNull(external.variableId)
            if (variable == null) {
                log.warn("Function {} is handed a variable that no longer exists", function.name)
                return@map "null"
            }
            instead[variable.name] ?: json(variable)
        }

    /** The names those arguments arrive under, for anything that has to say so. */
    fun namesOf(function: WorkflowFunction): List<String> = function.externals.mapNotNull { external ->
        variables.findByIdOrNull(external.variableId)?.name
    }

    private fun json(variable: WorkspaceVariable): String {
        val held = variable.value ?: return "null"
        return when (variable.type) {
            VariableType.STRING -> mapper.writeValueAsString(held)
            // Written as typed, checked here: a number nobody could parse is a
            // configuration mistake, and `null` is the honest version of it.
            VariableType.NUMBER -> held.trim().toBigDecimalOrNull()?.toString() ?: "null"
            VariableType.BOOLEAN -> when (held.trim().lowercase()) {
                "true" -> "true"
                "false" -> "false"
                else -> "null"
            }
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(VariableArguments::class.java)
    }
}
