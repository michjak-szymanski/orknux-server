package io.mszymanski.orknux.server.plugin

import io.mszymanski.orknux.connector.connection.ConnectionType
import io.mszymanski.orknux.server.action.ValueType
import io.mszymanski.orknux.server.agent.SkillFormat
import io.mszymanski.orknux.workflow.script.DeclaredFunction
import io.mszymanski.orknux.workflow.script.DeclaredParameter
import io.mszymanski.orknux.workflow.script.DeclaredSkill
import io.mszymanski.orknux.workflow.script.DeclaredTool
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

            Checked(function.name, function.description, params, returnType, function.source)
        }

        val array = mapper.createArrayNode()
        checked.forEach { function ->
            val node = array.addObject()
            node.put("name", function.name)
            function.description?.let { node.put("description", it) }
            node.put("returnType", function.returnType.name)
            // Kept as written, never validated: it is the plugin's own code,
            // shown in the editor for reference and executed from the bundle.
            function.source?.let { node.put("source", it) }
            val params = node.putArray("params")
            function.params.forEach { (name, type) ->
                params.addObject().put("name", name).put("type", type.name)
            }
        }
        return mapper.writeValueAsString(array)
    }

    /**
     * Checks what a plugin offers to agents and returns it as the JSON to keep.
     *
     * The same rules a function is held to - the names are identifiers, the
     * types are this server's - plus one of its own: a proxy names one of the
     * plugin's functions, which the inspection has already resolved, so what is
     * checked here is the copied shape and the reference is kept as it was
     * written. Tool names are unique among the tools; a tool sharing a name
     * with a function is fine, and is exactly what a proxy defaults to - the
     * two lists have different readers and never answer the same call.
     */
    fun validatedTools(declared: List<DeclaredTool>): String {
        val names = mutableSetOf<String>()

        val array = mapper.createArrayNode()
        declared.forEach { tool ->
            if (!IDENTIFIER.matches(tool.name)) {
                throw PluginDeclarationInvalidException("\"${tool.name}\" is not a usable tool name")
            }
            if (!names.add(tool.name)) {
                throw PluginDeclarationInvalidException("it declares the tool ${tool.name} more than once")
            }

            val returnType = valueType(tool.returnType)
                ?: throw PluginDeclarationInvalidException(
                    "the tool ${tool.name} returns \"${tool.returnType}\", which is not a type this server has",
                )
            if (returnType == ValueType.NONE || returnType == ValueType.OBJECT) {
                throw PluginDeclarationInvalidException(
                    "the tool ${tool.name} returns ${tool.returnType.lowercase()}; a tool answers a model, " +
                        "so it has to return one of ${usableTypes().joinToString(", ")}",
                )
            }

            val paramNames = mutableSetOf<String>()
            val params = tool.params.map { param ->
                if (!IDENTIFIER.matches(param.name)) {
                    throw PluginDeclarationInvalidException(
                        "the tool ${tool.name} has a parameter called \"${param.name}\", which is not a usable name",
                    )
                }
                if (!paramNames.add(param.name)) {
                    throw PluginDeclarationInvalidException("the tool ${tool.name} declares ${param.name} twice")
                }
                val type = valueType(param.type)
                    ?: throw PluginDeclarationInvalidException(
                        "the tool ${tool.name}'s ${param.name} is a \"${param.type}\", " +
                            "which is not a type this server has",
                    )
                param.name to type
            }

            val node = array.addObject()
            node.put("name", tool.name)
            tool.description?.let { node.put("description", it) }
            node.put("returnType", returnType.name)
            tool.proxyOf?.let { node.put("proxyOf", it) }
            val kept = node.putArray("params")
            params.forEach { (name, type) -> kept.addObject().put("name", name).put("type", type.name) }
        }
        return mapper.writeValueAsString(array)
    }

    /** What was kept about the tools, as the grant list and the dispatch want it. */
    fun readTools(json: String): List<PluginToolView> = runCatching {
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
            PluginToolView(
                name = node.get("name").asString(),
                description = node.get("description")?.asString(),
                params = read,
                returnType = node.get("returnType").asString(),
                proxyOf = node.get("proxyOf")?.asString(),
            )
        }
    }.getOrElse { emptyList() }

    /**
     * Checks the instruction sets a plugin brings, and returns them as JSON.
     *
     * A skill is held to the format a skill written in the interface is held
     * to — [SkillFormat] decides that, here as everywhere — with one kindness:
     * a plugin that wrote plain markdown and named the skill in its
     * declaration has the frontmatter written for it rather than being refused
     * for leaving out two facts it has already stated. What a plugin cannot do
     * is ship a *broken* block; a fence that opens and never closes is the
     * plugin's mistake and is refused as one.
     *
     * Names are held to the skill name rule rather than the identifier rule
     * the functions use: nothing calls a skill, an agent reads it, so "Handling
     * a stuck deploy" is a better name than `handling_a_stuck_deploy`.
     *
     * @throws PluginDeclarationInvalidException if anything about it is wrong.
     */
    fun validatedSkills(declared: List<DeclaredSkill>): String {
        val names = mutableSetOf<String>()

        val array = mapper.createArrayNode()
        declared.forEach { skill ->
            val name = skill.name.trim()
            if (name.isEmpty() || name.length > MOST_SKILL_NAME_CHARS) {
                throw PluginDeclarationInvalidException(
                    "\"${skill.name}\" is not a usable skill name: one is 1 to $MOST_SKILL_NAME_CHARS characters",
                )
            }
            if (!names.add(name.lowercase())) {
                throw PluginDeclarationInvalidException("it declares the skill $name more than once")
            }

            val description = skill.description?.trim()?.takeIf { it.isNotEmpty() }
            val content = frontmattered(name, description, skill.content)
            val check = SkillFormat.check(content)
            if (!check.valid) {
                throw PluginDeclarationInvalidException(
                    "the skill $name is not shaped like a skill: ${check.message?.lowercase()}",
                )
            }

            val node = array.addObject()
            node.put("name", name)
            description?.let { node.put("description", it) }
            node.put("content", content)
        }
        return mapper.writeValueAsString(array)
    }

    /**
     * The content as it will be stored: the plugin's own, or its markdown
     * under a block written from what it declared.
     *
     * Only for a body that opens with no fence at all. One that opens with a
     * fence is the plugin's own frontmatter and is left exactly as written —
     * including when it is wrong, which [SkillFormat] then says.
     */
    private fun frontmattered(name: String, description: String?, content: String): String {
        val first = content.lines().firstOrNull { it.isNotBlank() }?.trim()
        if (first == "---") return content
        return buildString {
            append("---\n")
            append("name: ").append(name).append('\n')
            append("description: ").append(description ?: "What this skill is for.").append('\n')
            append("---\n\n")
            append(content.trimStart())
        }
    }

    /** What was kept about the skills, as the catalog and the screen want it. */
    fun readSkills(json: String): List<PluginSkillView> = runCatching {
        val array = mapper.readTree(json)
        (0 until array.size()).map { at ->
            val node = array.get(at)
            PluginSkillView(
                name = node.get("name").asString(),
                description = node.get("description")?.asString(),
                content = node.get("content").asString(),
            )
        }
    }.getOrElse { emptyList() }

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

            /*
             * A connection is the one parameter that is neither typed in nor
             * read from a variable: it points at a row the workspace already
             * has, and what crosses into the sandbox is a handle to it rather
             * than anything the connection holds. So it is checked here on its
             * own terms and never against [SETTABLE], which is about scalars.
             */
            if (parameter.type.trim().equals(CONNECTION, ignoreCase = true)) {
                val kind = connectionType(parameter.connectionType)
                    ?: throw PluginDeclarationInvalidException(
                        "the parameter ${parameter.name} is a connection but does not say which kind. " +
                            "It has to name one of ${connectionTypes().joinToString(", ")}.",
                    )
                if (parameter.secret) {
                    /*
                     * Refused rather than ignored. A connection parameter holds
                     * no secret - it names a row, and the credential on that row
                     * is decrypted on the far side of the sandbox and never
                     * crosses it - so a plugin marking one secret has
                     * misunderstood what it is being given, and letting that
                     * through would leave the screen promising a protection that
                     * means nothing here.
                     */
                    throw PluginDeclarationInvalidException(
                        "the parameter ${parameter.name} is a connection and cannot be a secret: it names a " +
                            "connection rather than holding one's credential.",
                    )
                }

                val node = array.addObject()
                node.put("name", parameter.name)
                parameter.description?.let { node.put("description", it) }
                node.put("type", CONNECTION)
                node.put("connectionType", kind.name)
                node.put("required", parameter.required)
                node.put("secret", false)
                return@forEach
            }

            if (parameter.connectionType != null) {
                throw PluginDeclarationInvalidException(
                    "the parameter ${parameter.name} names a connection kind but is a \"${parameter.type}\".",
                )
            }

            val type = valueType(parameter.type)
            if (type == null || type !in SETTABLE) {
                throw PluginDeclarationInvalidException(
                    "the parameter ${parameter.name} is a \"${parameter.type}\". A parameter is either typed in, " +
                        "points at one of the workspace's variables, or names one of the workspace's " +
                        "connections, so it has to be one of " +
                        "${(parameterTypes() + CONNECTION).joinToString(", ")}.",
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
                connectionType = node.get("connectionType")?.asString(),
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
                source = node.get("source")?.asString(),
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

    /** The connection kinds a plugin may name, as it should write them. */
    fun connectionTypes(): List<String> = ConnectionType.entries.map { it.name }

    private fun connectionType(name: String?): ConnectionType? =
        name?.trim()?.let { wanted -> ConnectionType.entries.firstOrNull { it.name.equals(wanted, ignoreCase = true) } }

    private data class Checked(
        val name: String,
        val description: String?,
        val params: List<Pair<String, ValueType>>,
        val returnType: ValueType,
        val source: String? = null,
    )

    companion object {
        /** The same rule a workspace's own function names are held to. */
        val IDENTIFIER = Regex("[A-Za-z_$][A-Za-z0-9_$]{0,63}")

        /**
         * What a parameter may be: exactly what a workspace variable can hold.
         *
         * Written in the order they read best on a form rather than in the enum's
         * order, since this is also what the template offers.
         */
        val SETTABLE = listOf(ValueType.STRING, ValueType.NUMBER, ValueType.BOOLEAN)

        /**
         * How a plugin spells a parameter that names one of the workspace's
         * connections. Not a [ValueType]: those are what a value can be, and
         * this is a reference to a row.
         */
        const val CONNECTION = "connection"

        /** What the `agent_skill` name column holds. */
        const val MOST_SKILL_NAME_CHARS = 120
    }
}

class PluginDeclarationInvalidException(what: String) :
    RuntimeException("The plugin's functions could not be accepted: $what")
