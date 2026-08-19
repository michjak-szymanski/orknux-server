package io.mszymanski.orknux.server.transfer

import io.mszymanski.orknux.server.action.FunctionExternal
import io.mszymanski.orknux.server.action.FunctionParam
import io.mszymanski.orknux.server.action.FunctionScope
import io.mszymanski.orknux.server.action.ValueType
import io.mszymanski.orknux.server.action.WorkflowFunction
import io.mszymanski.orknux.server.action.WorkflowFunctionRepository
import io.mszymanski.orknux.server.agent.AgentSkill
import io.mszymanski.orknux.server.agent.AgentSkillRepository
import io.mszymanski.orknux.server.agent.AgentTool
import io.mszymanski.orknux.server.agent.AgentToolRepository
import io.mszymanski.orknux.server.agent.SkillCatalog
import io.mszymanski.orknux.server.agent.SkillCatalogRepository
import io.mszymanski.orknux.server.agent.SkillFormat
import io.mszymanski.orknux.server.condition.ConditionCheck
import io.mszymanski.orknux.server.condition.ConditionProperty
import io.mszymanski.orknux.server.condition.ConditionType
import io.mszymanski.orknux.server.condition.WorkflowCondition
import io.mszymanski.orknux.server.condition.WorkflowConditionRepository
import io.mszymanski.orknux.server.obj.ObjectProperty
import io.mszymanski.orknux.server.obj.PropertyKind
import io.mszymanski.orknux.server.obj.WorkflowObject
import io.mszymanski.orknux.server.obj.WorkflowObjectRepository
import io.mszymanski.orknux.server.variable.WorkspaceVariableRepository
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime

/**
 * Reads an envelope, says what it would do, and — separately — does it.
 *
 * The two are the same walk over the same parsed file, which is what makes the
 * preview trustworthy: [plan] and [apply] disagree only in that the second one
 * saves. Anything the first says is impossible, the second refuses outright,
 * and it refuses before writing rather than partway through.
 */
@Service
class ComponentImporter(
    private val objects: WorkflowObjectRepository,
    private val functions: WorkflowFunctionRepository,
    private val conditions: WorkflowConditionRepository,
    private val tools: AgentToolRepository,
    private val skills: AgentSkillRepository,
    private val catalogs: SkillCatalogRepository,
    private val variables: WorkspaceVariableRepository,
    private val auditRecorder: WorkspaceAuditRecorder,
    private val mapper: ObjectMapper,
) {

    /** What the confirmation dialog shows. Reads nothing into the workspace. */
    fun plan(workspaceId: Long, envelope: String): ImportPlan = plan(workspaceId, parse(envelope))

    /**
     * Creates everything the plan said it would, or nothing.
     *
     * One transaction, and the refusal for an unresolvable reference happens
     * before the first save — an import that got halfway would leave a function
     * typed against an object that is not there, which is worse than no import.
     */
    @Transactional
    fun apply(workspaceId: Long, envelope: String): ImportPlan {
        val parsed = parse(envelope)
        val plan = plan(workspaceId, parsed)
        if (!plan.importable) throw ImportNotPossibleException(plan.problems)

        // Every reference resolves through this: an envelope name to the id it
        // means here. Filled as things are created, so a renamed component is
        // pointed at by its new id without anything else having to know it moved.
        val resolved = mutableMapOf<Pair<ComponentKind, String>, Long>()
        val named = plan.entries.filter { it.kind != null && it.disposition != ImportDisposition.REUSE }
            .associate { (it.kind!! to it.name) to it.targetName }

        /*
         * The order things are written in is not a tidiness question, it is what
         * the database will accept. `ck_workflow_function_return_object` and
         * `ck_workflow_condition_shape` are row-level checks: a function that
         * says it returns an object has to name one in the same INSERT, and a
         * composite condition has to have its members. So neither can be created
         * empty and filled in afterwards, and both are created complete, after
         * whatever they point at.
         *
         * An object is the exception, and the reason it can be: its properties
         * are rows in a table of their own, so the object exists before any of
         * them do. That is what lets an object hold a property of its own type,
         * which is how a tree is described, and it is why objects are the one
         * kind written in two passes.
         */
        val objects = parsed.components.filter { it.kind == ComponentKind.OBJECT }
        val written = objects +
            parsed.components.filter { it.kind == ComponentKind.FUNCTION } +
            dependenciesFirst(parsed.components.filter { it.kind == ComponentKind.CONDITION }) +
            parsed.components.filter { it.kind == ComponentKind.TOOL || it.kind == ComponentKind.SKILL }

        objects.forEach { component ->
            resolved[component.kind to component.name] =
                createObject(workspaceId, component, named.getValue(component.kind to component.name))
        }
        objects.forEach { component -> wireObject(workspaceId, component, resolved) }
        written.filter { it.kind != ComponentKind.OBJECT }.forEach { component ->
            val here = named.getValue(component.kind to component.name)
            resolved[component.kind to component.name] = create(workspaceId, component, here, resolved)
        }

        plan.entries.filter { it.kind != null && it.disposition != ImportDisposition.REUSE }.forEach { entry ->
            val said = if (entry.disposition == ImportDisposition.RENAME) {
                "${entry.kind!!.label.replaceFirstChar(Char::uppercase)} ${entry.name} imported as ${entry.targetName}"
            } else {
                "${entry.kind!!.label.replaceFirstChar(Char::uppercase)} ${entry.targetName} imported"
            }
            auditRecorder.record(workspaceId, categoryOf(entry.kind!!), said)
        }
        return plan
    }

    // ---------------------------------------------------------------- planning

    private fun plan(workspaceId: Long, parsed: Parsed): ImportPlan {
        val carried = parsed.components.map { it.kind to it.name }.toSet()
        val entries = mutableListOf<ImportEntry>()
        val problems = mutableListOf<String>()

        // A component's name here, decided before anything else looks at it:
        // what points at it has to point at the name it will actually have.
        val taken = mutableMapOf<ComponentKind, MutableSet<String>>()
        val here = mutableMapOf<Pair<ComponentKind, String>, String>()
        parsed.components.forEach { component ->
            val claimed = taken.getOrPut(component.kind) { mutableSetOf() }
            val name = freeName(workspaceId, component.kind, component.name, claimed)
            claimed.add(name)
            here[component.kind to component.name] = name
            entries += if (name == component.name) {
                ImportEntry(
                    kind = component.kind,
                    name = component.name,
                    targetName = name,
                    disposition = ImportDisposition.CREATE,
                    detail = "New ${component.kind.label} in this workspace",
                )
            } else {
                ImportEntry(
                    kind = component.kind,
                    name = component.name,
                    targetName = name,
                    disposition = ImportDisposition.RENAME,
                    detail = "This workspace already has ${component.kind.indefinite} called ${component.name}, " +
                        "so this one arrives as $name. Everything else in this file that pointed at it " +
                        "points at $name.",
                )
            }
        }

        // Then every reference. One that the file carries is settled above; one
        // it does not is the target workspace's to satisfy, by name.
        val seen = mutableSetOf<Pair<ComponentKind, String>>()
        parsed.components.forEach { component ->
            referencesOf(component).forEach { (kind, name) ->
                if (kind to name in carried) return@forEach
                if (!seen.add(kind to name)) return@forEach
                val existing = findByName(workspaceId, kind, name)
                if (existing == null) {
                    entries += ImportEntry(
                        kind = kind,
                        name = name,
                        targetName = name,
                        disposition = ImportDisposition.MISSING,
                        detail = "${component.name} points at ${kind.indefinite} called $name, which this file " +
                            "does not carry and this workspace does not have. Export again with everything " +
                            "included, or create it here first.",
                    )
                    problems += "There is no ${kind.label} called $name here, and ${component.name} needs one."
                } else {
                    entries += ImportEntry(
                        kind = kind,
                        name = name,
                        targetName = name,
                        disposition = ImportDisposition.REUSE,
                        detail = "Already here; the imported ${component.name} will point at it.",
                    )
                }
            }
        }

        // Variables last, because they are the ones somebody has to go and make.
        parsed.variables.forEach { variable ->
            val existing = variables.findByWorkspaceId(workspaceId).firstOrNull { it.name == variable.name }
            if (existing == null) {
                entries += ImportEntry(
                    kind = null,
                    name = variable.name,
                    targetName = variable.name,
                    disposition = ImportDisposition.MISSING,
                    detail = "A ${variable.type.lowercase()} variable called ${variable.name} is handed to one of " +
                        "these functions. Values never travel in an export, so create it here with its own " +
                        "value first.",
                )
                problems += "This workspace has no variable called ${variable.name}; " +
                    "a variable's value is never exported, so it has to be created here."
            } else {
                entries += ImportEntry(
                    kind = null,
                    name = variable.name,
                    targetName = existing.name,
                    disposition = ImportDisposition.REUSE,
                    detail = "Already here. Its value is this workspace's own; nothing came from the file.",
                )
            }
        }

        return ImportPlan(
            formatVersion = parsed.formatVersion,
            producedBy = parsed.producedBy,
            depth = parsed.depth,
            importable = problems.isEmpty(),
            entries = entries,
            problems = problems,
        )
    }

    /** Every reference one component makes, as kind and envelope name. */
    private fun referencesOf(component: ParsedComponent): List<Pair<ComponentKind, String>> {
        val node = component.node
        return when (component.kind) {
            ComponentKind.OBJECT -> node.path("properties").values().mapNotNull { it.text("objectRef") }
                .map { ComponentKind.OBJECT to it }

            ComponentKind.FUNCTION ->
                (
                    node.path("params").values().mapNotNull { it.text("objectRef") } +
                        listOfNotNull(node.text("returnObjectRef"))
                    ).map { ComponentKind.OBJECT to it }

            ComponentKind.CONDITION ->
                node.names("memberRefs").map { ComponentKind.CONDITION to it } +
                    listOfNotNull(node.text("functionRef")).map { ComponentKind.FUNCTION to it }

            ComponentKind.TOOL, ComponentKind.SKILL -> emptyList()
        }
    }

    // ----------------------------------------------------------------- writing

    /**
     * The conditions of an envelope, each one after the ones it combines.
     *
     * A composite has to name its members in the INSERT that creates it, so the
     * members have to exist first. The catalogue refuses a cycle when a condition
     * is saved, so a well-formed file has none; one that arrives with a cycle is
     * a hand-edited file, and it is refused rather than looped over.
     */
    private fun dependenciesFirst(held: List<ParsedComponent>): List<ParsedComponent> {
        val byName = held.associateBy { it.name }
        val ordered = LinkedHashSet<ParsedComponent>()
        val placing = mutableSetOf<String>()

        fun place(component: ParsedComponent) {
            if (component in ordered) return
            if (!placing.add(component.name)) {
                throw EnvelopeInvalidException("The condition ${component.name} would contain itself")
            }
            component.node.names("memberRefs").mapNotNull { byName[it] }.forEach(::place)
            placing.remove(component.name)
            ordered.add(component)
        }

        held.forEach(::place)
        return ordered.toList()
    }

    /** An object's own row, so that an id exists for a property to point at. */
    private fun createObject(workspaceId: Long, component: ParsedComponent, name: String): Long {
        val now = OffsetDateTime.now()
        val who = currentUser()
        return objects.save(
            WorkflowObject(
                workspaceId = workspaceId,
                name = name,
                description = component.node.text("description"),
                createdAt = now,
                createdBy = who,
                lastModifiedAt = now,
                lastModifiedBy = who,
            ),
        ).id!!
    }

    /** An object's properties, once every object in the file has an id. */
    private fun wireObject(
        workspaceId: Long,
        component: ParsedComponent,
        resolved: Map<Pair<ComponentKind, String>, Long>,
    ) {
        val held = objects.findById(resolved.getValue(ComponentKind.OBJECT to component.name)).orElseThrow()
        held.properties = component.node.path("properties").values().map { property ->
            ObjectProperty(
                name = property.text("name").orEmpty(),
                kind = property.enumOf<PropertyKind>("kind", null, component),
                refObjectId = property.text("objectRef")
                    ?.let { idFor(workspaceId, ComponentKind.OBJECT, it, resolved) },
                elementKind = property.enumOrNull<PropertyKind>("elementKind", component),
            )
        }.toMutableList()
    }

    /**
     * Everything that is not an object, created whole.
     *
     * Whole because it has to be: the row-level checks on a function's return
     * type and on a condition's shape are satisfied by the INSERT or not at all.
     */
    private fun create(
        workspaceId: Long,
        component: ParsedComponent,
        name: String,
        resolved: Map<Pair<ComponentKind, String>, Long>,
    ): Long {
        val node = component.node
        val now = OffsetDateTime.now()
        val who = currentUser()
        fun idOf(kind: ComponentKind, of: String) = idFor(workspaceId, kind, of, resolved)

        return when (component.kind) {
            // Written by createObject, in two passes; nothing reaches here.
            ComponentKind.OBJECT -> error("An object is created before this")

            ComponentKind.FUNCTION -> functions.save(
                WorkflowFunction(
                    workspaceId = workspaceId,
                    // Always the workspace's own. A plugin's function belongs to
                    // the plugin that declared it and is not importable at all.
                    scope = FunctionScope.WORKSPACE,
                    name = name,
                    description = node.text("description"),
                    source = node.required("source", component),
                    typescript = node.required("typescript", component),
                    returnType = node.enumOf("returnType", ValueType.MAP, component),
                    returnObjectId = node.text("returnObjectRef")?.let { idOf(ComponentKind.OBJECT, it) },
                    params = node.path("params").values().map { param ->
                        FunctionParam(
                            name = param.text("name").orEmpty(),
                            type = param.enumOf<ValueType>("type", null, component),
                            objectId = param.text("objectRef")?.let { idOf(ComponentKind.OBJECT, it) },
                        )
                    }.toMutableList(),
                    // Order is the signature: externals are passed after the
                    // declared parameters, in this order, and the code was
                    // written against that.
                    externals = node.names("variableRefs").map { of ->
                        val variable = variables.findByWorkspaceId(workspaceId).firstOrNull { it.name == of }
                            ?: throw EnvelopeInvalidException("No variable called $of in this workspace")
                        FunctionExternal(variableId = variable.id!!)
                    }.toMutableList(),
                    lastModifiedAt = now,
                    lastModifiedBy = who,
                ),
            ).id!!

            ComponentKind.CONDITION -> conditions.save(
                WorkflowCondition(
                    workspaceId = workspaceId,
                    name = name,
                    type = node.enumOf<ConditionType>("type", null, component),
                    property = node.enumOrNull<ConditionProperty>("property", component),
                    check = node.enumOrNull<ConditionCheck>("check", component),
                    negate = node.path("negate").asBoolean(false),
                    functionId = node.text("functionRef")?.let { idOf(ComponentKind.FUNCTION, it) },
                    values = node.names("values").toMutableList(),
                    members = node.names("memberRefs")
                        .map { idOf(ComponentKind.CONDITION, it) }
                        .toMutableList(),
                    icon = node.text("icon"),
                ),
            ).id!!

            ComponentKind.TOOL -> tools.save(
                AgentTool(
                    workspaceId = workspaceId,
                    name = name,
                    description = node.text("description"),
                    source = node.required("source", component),
                    typescript = node.required("typescript", component),
                    enabled = node.path("enabled").asBoolean(true),
                    lastModifiedAt = now,
                    lastModifiedBy = who,
                ),
            ).id!!

            ComponentKind.SKILL -> {
                val content = node.required("content", component)
                val checked = SkillFormat.check(content)
                if (!checked.valid) {
                    throw EnvelopeInvalidException(
                        "The skill ${component.name} is not shaped like a skill: ${checked.message}",
                    )
                }
                skills.save(
                    AgentSkill(
                        workspaceId = workspaceId,
                        catalogId = catalogFor(workspaceId, node.text("catalog"), who),
                        name = name,
                        description = node.text("description"),
                        content = content,
                        enabled = node.path("enabled").asBoolean(true),
                        lastModifiedAt = now,
                        lastModifiedBy = who,
                    ),
                ).id!!
            }
        }
    }

    /**
     * An envelope name to the id it means here.
     *
     * What arrived with the file first, so a renamed component is pointed at by
     * what came with it rather than by something of the same name that was
     * already here; then the workspace's own, which is how a shallow export lands.
     */
    private fun idFor(
        workspaceId: Long,
        kind: ComponentKind,
        name: String,
        resolved: Map<Pair<ComponentKind, String>, Long>,
    ): Long = resolved[kind to name]
        ?: findByName(workspaceId, kind, name)
        ?: throw EnvelopeInvalidException("No ${kind.label} called $name to point at")

    /**
     * The folder an imported skill lands in.
     *
     * Reused when the workspace has one by that name and created when it does
     * not — the only thing here that is matched rather than renamed, because a
     * catalog holds nothing but a name. Making a second "General" beside the
     * first would be a worse answer than putting the skill in the first.
     */
    private fun catalogFor(workspaceId: Long, name: String?, who: String): Long {
        val wanted = name?.trim()?.ifEmpty { null } ?: DEFAULT_CATALOG
        catalogs.findByWorkspaceIdAndName(workspaceId, wanted)?.let { return it.id!! }
        return catalogs.save(SkillCatalog(workspaceId = workspaceId, name = wanted, createdBy = who)).id!!
    }

    // ------------------------------------------------------------------ naming

    /**
     * The name this arrives under: its own, or the first free one after it.
     *
     * [claimed] holds what earlier components in the same envelope have already
     * taken, so two things that would land on the same free name do not.
     */
    private fun freeName(
        workspaceId: Long,
        kind: ComponentKind,
        wanted: String,
        claimed: Set<String>,
    ): String {
        requireUsable(kind, wanted)
        fun free(name: String) = name !in claimed && findByName(workspaceId, kind, name) == null &&
            // A workspace function may not shadow one a plugin declared: a call
            // by name would have no way to say which of the two it meant.
            (kind != ComponentKind.FUNCTION || functions.findByScopeAndName(FunctionScope.PLUGIN, name) == null)

        if (free(wanted)) return wanted
        for (attempt in 2..MAX_RENAME) {
            val candidate = suffixed(kind, wanted, attempt)
            if (free(candidate)) return candidate
        }
        throw EnvelopeInvalidException(
            "This workspace already has $MAX_RENAME ${kind.label}s called $wanted or something like it. " +
                "Rename one of them, or rename this in the file before importing.",
        )
    }

    /**
     * `normalise` becomes `normalise_2`, `Out of hours` becomes `Out of hours (2)`.
     *
     * Two spellings because two of these names are identifiers: a function and a
     * tool are called from JavaScript by name, and an object is written into a
     * type annotation, so a space or a bracket in one of those is not a rename
     * but a break. Conditions and skills are prose and read better with the
     * bracket.
     */
    private fun suffixed(kind: ComponentKind, base: String, attempt: Int): String {
        val suffix = if (identifierNamed(kind)) "_$attempt" else " ($attempt)"
        val limit = limitFor(kind) - suffix.length
        return base.take(limit.coerceAtLeast(1)) + suffix
    }

    private fun requireUsable(kind: ComponentKind, name: String) {
        val ok = when {
            name.isBlank() -> false
            name.length > limitFor(kind) -> false
            identifierNamed(kind) -> IDENTIFIER.matches(name)
            else -> true
        }
        if (!ok) throw EnvelopeInvalidException("\"$name\" is not a name a ${kind.label} can have here")
    }

    private fun identifierNamed(kind: ComponentKind): Boolean =
        kind == ComponentKind.OBJECT || kind == ComponentKind.FUNCTION || kind == ComponentKind.TOOL

    private fun limitFor(kind: ComponentKind): Int = when (kind) {
        // The width of the column, and for the three identifiers what the name
        // regexes already say. A rename that overflowed either would fail on save.
        ComponentKind.OBJECT, ComponentKind.FUNCTION, ComponentKind.TOOL -> 64
        ComponentKind.CONDITION, ComponentKind.SKILL -> 120
    }

    private fun findByName(workspaceId: Long, kind: ComponentKind, name: String): Long? = when (kind) {
        ComponentKind.OBJECT -> objects.findByWorkspaceIdAndName(workspaceId, name)?.id
        ComponentKind.FUNCTION -> functions.findByWorkspaceIdAndName(workspaceId, name)?.id
        ComponentKind.CONDITION -> conditions.findByWorkspaceIdAndName(workspaceId, name)?.id
        ComponentKind.TOOL -> tools.findByWorkspaceIdAndName(workspaceId, name)?.id
        ComponentKind.SKILL -> skills.findByWorkspaceIdAndName(workspaceId, name)?.id
    }

    private fun categoryOf(kind: ComponentKind): WorkspaceAuditCategory = when (kind) {
        ComponentKind.OBJECT -> WorkspaceAuditCategory.OBJECT
        ComponentKind.FUNCTION, ComponentKind.CONDITION -> WorkspaceAuditCategory.WORKFLOW
        ComponentKind.TOOL, ComponentKind.SKILL -> WorkspaceAuditCategory.AGENT
    }

    private fun currentUser(): String = SecurityContextHolder.getContext().authentication?.name ?: "system"

    // ----------------------------------------------------------------- reading

    /**
     * The file, checked before anything in it is believed.
     *
     * The version is the first thing read and the first thing that can refuse,
     * because everything after it is only meaningful under a version this
     * understands.
     */
    private fun parse(envelope: String): Parsed {
        val root = try {
            mapper.readTree(envelope)
        } catch (cause: JacksonException) {
            throw EnvelopeUnreadableException("it is not valid JSON (${cause.originalMessage})")
        }
        if (!root.isObject) throw EnvelopeUnreadableException("the file does not hold a JSON object")

        val producedBy = root.text("producedBy")
        val version = root.path("formatVersion")
        if (!version.isIntegralNumber) {
            throw EnvelopeUnreadableException("it carries no formatVersion, so there is no telling what it is")
        }
        val found = version.asInt()
        if (found < 1 || found > COMPONENT_FORMAT_VERSION) throw EnvelopeVersionUnknownException(found, producedBy)

        val components = root.path("components").values().mapIndexed { index, node ->
            if (!node.isObject) throw EnvelopeInvalidException("Component ${index + 1} is not an object")
            val kind = runCatching { ComponentKind.valueOf(node.text("kind").orEmpty()) }.getOrElse {
                throw EnvelopeInvalidException(
                    "Component ${index + 1} is a \"${node.text("kind")}\", which this installation does not import",
                )
            }
            val name = node.text("name")?.trim()?.ifEmpty { null }
                ?: throw EnvelopeInvalidException("Component ${index + 1} has no name")
            ParsedComponent(kind, name, node)
        }
        if (components.isEmpty()) throw EnvelopeInvalidException("This export holds nothing to import")
        components.groupBy { it.kind to it.name }.filterValues { it.size > 1 }.keys.firstOrNull()?.let { (kind, name) ->
            throw EnvelopeInvalidException("This export holds two ${kind.label}s called $name")
        }

        val variables = root.path("requires").path("variables").values().map { node ->
            RequiredVariable(
                name = node.text("name")?.trim()?.ifEmpty { null }
                    ?: throw EnvelopeInvalidException("A required variable has no name"),
                type = node.text("type") ?: "STRING",
                secret = node.path("secret").asBoolean(true),
            )
        }

        return Parsed(
            formatVersion = found,
            producedBy = producedBy,
            depth = runCatching { ExportDepth.valueOf(root.text("depth").orEmpty()) }.getOrDefault(ExportDepth.DEEP),
            components = components,
            variables = variables,
        )
    }

    private class Parsed(
        val formatVersion: Int,
        val producedBy: String?,
        val depth: ExportDepth,
        val components: List<ParsedComponent>,
        val variables: List<RequiredVariable>,
    )

    private class ParsedComponent(val kind: ComponentKind, val name: String, val node: JsonNode)

    private class RequiredVariable(val name: String, val type: String, val secret: Boolean)

    private companion object {
        /** Matches what a function and a tool already require of a name. */
        val IDENTIFIER = Regex("[A-Za-z_$][A-Za-z0-9_$]{0,63}")

        /** Where an imported skill goes when the file names no folder. */
        const val DEFAULT_CATALOG = "General"

        /** Far past any honest collision; a bound, so a loop cannot be one. */
        const val MAX_RENAME = 100

        fun JsonNode.text(field: String): String? = path(field).takeIf { it.isString }?.stringValue()

        /**
         * An array of names, with anything that is not a name left out.
         *
         * `stringValue` refuses a node that is not text rather than coercing it,
         * so a hand-edited file holding a number where a name belongs is skipped
         * here and reported by the plan as a reference that does not resolve.
         */
        fun JsonNode.names(field: String): List<String> =
            path(field).values().mapNotNull { it.takeIf(JsonNode::isString)?.stringValue() }

        fun JsonNode.required(field: String, component: ParsedComponent): String =
            text(field) ?: throw EnvelopeInvalidException(
                "The ${component.kind.label} ${component.name} has no $field",
            )

        /** An enum by name, refusing a spelling this version has never heard of. */
        inline fun <reified E : Enum<E>> JsonNode.enumOf(field: String, fallback: E?, component: ParsedComponent): E {
            val said = text(field) ?: return fallback ?: throw EnvelopeInvalidException(
                "The ${component.kind.label} ${component.name} has no $field",
            )
            return runCatching { enumValueOf<E>(said) }.getOrElse {
                throw EnvelopeInvalidException(
                    "The ${component.kind.label} ${component.name} says its $field is \"$said\", " +
                        "which this installation does not know",
                )
            }
        }

        inline fun <reified E : Enum<E>> JsonNode.enumOrNull(field: String, component: ParsedComponent): E? {
            val said = text(field) ?: return null
            return runCatching { enumValueOf<E>(said) }.getOrElse {
                throw EnvelopeInvalidException(
                    "The ${component.kind.label} ${component.name} says its $field is \"$said\", " +
                        "which this installation does not know",
                )
            }
        }
    }
}

/**
 * What the import will do about one thing the envelope mentions.
 *
 * [kind] is null for a variable, which is the one thing here that is pointed at
 * and never created.
 */
data class ImportEntry(
    val kind: ComponentKind?,
    /** The name in the file. */
    val name: String,
    /** The name it will have here, which differs when it was renamed. */
    val targetName: String,
    val disposition: ImportDisposition,
    /** Why, in words a screen shows unchanged. */
    val detail: String,
)

/**
 * What an import would do, shown before it is done.
 *
 * A button that silently creates nine things is worse than one that lists them,
 * so this is what the confirmation dialog reads and it is also what the mutation
 * answers afterwards — the same shape either way, so a client cannot show one
 * thing and get another.
 */
data class ImportPlan(
    val formatVersion: Int,
    /** Quoted in messages only. Nothing branches on it. */
    val producedBy: String?,
    val depth: ExportDepth,
    val importable: Boolean,
    val entries: List<ImportEntry>,
    /** Empty when importable; otherwise what has to be fixed first. */
    val problems: List<String>,
)
