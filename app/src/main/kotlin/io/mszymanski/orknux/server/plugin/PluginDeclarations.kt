package io.mszymanski.orknux.server.plugin

import io.mszymanski.orknux.server.action.ValueType
import io.mszymanski.orknux.workflow.script.DeclaredFunction
import io.mszymanski.orknux.workflow.script.DeclaredParameter
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * What a plugin declared, checked and kept.
 *
 * A plugin answers with names and type names of its own choosing, so this is
 * where that answer stops being the plugin's word for something and becomes the
 * server's: every name has to be a JavaScript identifier, because that is what a
 * function is called by, and every type has to be one this server has.
 *
 * A plugin whose declarations do not survive that is refused at upload. There is
 * no partial acceptance — half a plugin's functions is not a plugin somebody can
 * write a workflow against.
 *
 * Read and written with the tree API rather than by binding to data classes:
 * there is no Jackson Kotlin module on the classpath, so a data class has no
 * usable constructor to bind to, and the rest of this codebase reads JSON the
 * same way.
 */
@Component
class PluginDeclarations(private val mapper: ObjectMapper) {

    /**
     * Checks what a plugin answered and returns it as the JSON to keep.
     *
     * @throws PluginDeclarationInvalidException if anything about it is wrong.
     */
    fun validated(declared: List<DeclaredFunction>): String {
        val names = mutableSetOf<String>()

        val checked = declared.map { function ->
            if (!IDENTIFIER.matches(function.name)) {
                throw PluginDeclarationInvalidException("\"${function.name}\" is not a usable function name")
            }
            if (!names.add(function.name)) {
                throw PluginDeclarationInvalidException("it declares ${function.name} more than once")
            }

            val returnType = valueType(function.returnType)
                ?: throw PluginDeclarationInvalidException(
                    "${function.name} returns \"${function.returnType}\", which is not a type this server has",
                )
            /*
             * A function's return type is constrained in the database to the types
             * that carry a value; NONE is for things that act rather than answer,
             * and a function is not one of those.
             */
            if (returnType == ValueType.NONE) {
                throw PluginDeclarationInvalidException("${function.name} must return something, not none")
            }
            // The same reason as a parameter's: there is no workspace here whose
            // object it could be naming.
            if (returnType == ValueType.OBJECT) {
                throw PluginDeclarationInvalidException(
                    "${function.name} returns an object, which names one of a workspace's definitions. A " +
                        "plugin's functions belong to every workspace at once, so use map instead.",
                )
            }

            val paramNames = mutableSetOf<String>()
            val params = function.params.map { param ->
                if (!IDENTIFIER.matches(param.name)) {
                    throw PluginDeclarationInvalidException(
                        "${function.name} has a parameter called \"${param.name}\", which is not a usable name",
                    )
                }
                if (!paramNames.add(param.name)) {
                    throw PluginDeclarationInvalidException("${function.name} declares ${param.name} twice")
                }
                val type = valueType(param.type)
                    ?: throw PluginDeclarationInvalidException(
                        "${function.name}'s ${param.name} is a \"${param.type}\", which is not a type this server has",
                    )
                if (type == ValueType.OBJECT) {
                    throw PluginDeclarationInvalidException(
                        "${function.name}'s ${param.name} is an object, which names one of a workspace's " +
                            "definitions. A plugin's functions belong to every workspace at once, so there is " +
                            "no workspace whose objects they could name. Use map instead.",
                    )
                }
                param.name to type
            }

            Checked(function.name, function.description, params, returnType)
        }

        val array = mapper.createArrayNode()
        checked.forEach { function ->
            val node = array.addObject()
            node.put("name", function.name)
            function.description?.let { node.put("description", it) }
            node.put("returnType", function.returnType.name)
            val params = node.putArray("params")
            function.params.forEach { (name, type) ->
                params.addObject().put("name", name).put("type", type.name)
            }
        }
        return mapper.writeValueAsString(array)
    }

    /**
     * Checks what a plugin says it has to be told, and returns it as JSON to keep.
     *
     * Held to the same naming rule as everything else a plugin declares, and to a
     * narrower set of types: a parameter is filled in either by typing a value or
     * by pointing at one of the workspace's variables, and a variable holds a
     * scalar. Allowing a map here would mean a parameter that can be typed but
     * never referenced, which is a difference nobody could see on the screen and
     * everybody would trip over.
     *
     * @throws PluginDeclarationInvalidException if anything about it is wrong.
     */
    fun validatedParameters(declared: List<DeclaredParameter>): String {
        val names = mutableSetOf<String>()

        val array = mapper.createArrayNode()
        declared.forEach { parameter ->
            if (!IDENTIFIER.matches(parameter.name)) {
                throw PluginDeclarationInvalidException("\"${parameter.name}\" is not a usable parameter name")
            }
            if (!names.add(parameter.name)) {
                throw PluginDeclarationInvalidException("it declares the parameter ${parameter.name} more than once")
            }

            val type = valueType(parameter.type)
            if (type == null || type !in SETTABLE) {
                throw PluginDeclarationInvalidException(
                    "the parameter ${parameter.name} is a \"${parameter.type}\". A parameter is either typed in " +
                        "or points at one of the workspace's variables, so it has to be one of " +
                        "${parameterTypes().joinToString(", ")}.",
                )
            }

            val node = array.addObject()
            node.put("name", parameter.name)
            parameter.description?.let { node.put("description", it) }
            node.put("type", type.name)
            node.put("required", parameter.required)
            node.put("secret", parameter.secret)
        }
        return mapper.writeValueAsString(array)
    }

    /** What was kept about the parameters, as the screen wants it. */
    fun readParameters(json: String): List<PluginParameterView> = runCatching {
        val array = mapper.readTree(json)
        (0 until array.size()).map { at ->
            val node = array.get(at)
            PluginParameterView(
                name = node.get("name").asString(),
                description = node.get("description")?.asString(),
                type = node.get("type").asString(),
                /*
                 * A declaration written before these existed has neither, and
                 * the safe reading of silence is "not required, not a secret".
                 *
                 * Deliberately the opposite of what the contract's own
                 * constructor does, which defaults `required` to true. The two
                 * are answering different questions: a plugin author who omits
                 * it means the parameter matters, while a stored row that omits
                 * it is one written before parameters existed, and marking that
                 * as missing something would put a red mark on a plugin that
                 * never asked for anything. Anything the constructor wrote has
                 * the key, so this only ever reads the old shape.
                 */
                required = node.get("required")?.asBoolean() ?: false,
                secret = node.get("secret")?.asBoolean() ?: false,
            )
        }
    }.getOrElse { emptyList() }

    /**
     * The types a parameter may be, as a plugin should write them.
     *
     * Narrower than [usableTypes] on purpose; see [validatedParameters] for why.
     */
    fun parameterTypes(): List<String> = SETTABLE.map { it.name.lowercase() }

    /**
     * The type names a plugin may use, as it should write them.
     *
     * Derived from the enum rather than listed again, so the template hands out
     * exactly what [validated] will accept.
     *
     * Two are left out. NONE means "answers nothing", and neither a parameter nor a
     * function's result may be that. OBJECT names one of a workspace's objects, and
     * a plugin's functions belong to the organisation — they are available in every
     * workspace at once, so there is no single workspace whose objects they could
     * point at. A plugin that wants a structure asks for a map.
     */
    fun usableTypes(): List<String> = ValueType.entries
        .filter { it != ValueType.NONE && it != ValueType.OBJECT }
        .map { it.name.lowercase() }

    /** What was kept, as the screen wants it. Unreadable JSON reads as nothing declared. */
    fun read(json: String): List<PluginFunctionView> = runCatching {
        val array = mapper.readTree(json)
        (0 until array.size()).map { at ->
            val node = array.get(at)
            val params = node.get("params")
            val read = (0 until (params?.size() ?: 0)).map { index ->
                val param = params.get(index)
                PluginFunctionParamView(
                    name = param.get("name").asString(),
                    type = param.get("type").asString(),
                )
            }
            val returnType = node.get("returnType").asString()
            PluginFunctionView(
                name = node.get("name").asString(),
                description = node.get("description")?.asString(),
                params = read,
                returnType = returnType,
                signature = signature(read, returnType),
            )
        }
    }.getOrElse { emptyList() }

    /** "(email: string): boolean", the way a workspace's own functions read. */
    private fun signature(params: List<PluginFunctionParamView>, returnType: String): String {
        val taken = params.joinToString(", ") { "${it.name}: ${it.type.lowercase()}" }
        return "($taken): ${returnType.lowercase()}"
    }

    private fun valueType(name: String): ValueType? =
        ValueType.entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }

    private data class Checked(
        val name: String,
        val description: String?,
        val params: List<Pair<String, ValueType>>,
        val returnType: ValueType,
    )

    private companion object {
        /** The same rule a workspace's own function names are held to. */
        val IDENTIFIER = Regex("[A-Za-z_$][A-Za-z0-9_$]{0,63}")

        /**
         * What a parameter may be: exactly what a workspace variable can hold.
         *
         * Written in the order they read best on a form rather than in the enum's
         * order, since this is also what the template offers.
         */
        val SETTABLE = listOf(ValueType.STRING, ValueType.NUMBER, ValueType.BOOLEAN)
    }
}

class PluginDeclarationInvalidException(what: String) :
    RuntimeException("The plugin's functions could not be accepted: $what")
