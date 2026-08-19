package io.mszymanski.orknux.server.transfer

import io.mszymanski.orknux.server.action.WorkflowFunction
import io.mszymanski.orknux.server.action.WorkflowFunctionRepository
import io.mszymanski.orknux.server.agent.AgentSkill
import io.mszymanski.orknux.server.agent.AgentSkillRepository
import io.mszymanski.orknux.server.agent.AgentTool
import io.mszymanski.orknux.server.agent.AgentToolRepository
import io.mszymanski.orknux.server.agent.SkillCatalogRepository
import io.mszymanski.orknux.server.condition.WorkflowCondition
import io.mszymanski.orknux.server.condition.WorkflowConditionRepository
import io.mszymanski.orknux.server.obj.WorkflowObject
import io.mszymanski.orknux.server.obj.WorkflowObjectRepository
import io.mszymanski.orknux.server.variable.WorkspaceVariableRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * Turns one of a workspace's components into the JSON somebody downloads.
 *
 * Walks outward from the thing asked for, collecting what it reaches, and writes
 * every one of them by name. Nothing here reads a credential: a variable a
 * function is handed appears as a name and a shape under `requires`, which is
 * the whole of what the target needs in order to say what to point it at.
 */
@Service
class ComponentExporter(
    private val objects: WorkflowObjectRepository,
    private val functions: WorkflowFunctionRepository,
    private val conditions: WorkflowConditionRepository,
    private val tools: AgentToolRepository,
    private val skills: AgentSkillRepository,
    private val catalogs: SkillCatalogRepository,
    private val variables: WorkspaceVariableRepository,
    private val mapper: ObjectMapper,
    @Value("\${orknux.version:unknown}") private val version: String,
) {

    /**
     * The envelope, formatted, for one component of one workspace.
     *
     * The caller has already decided the workspace is the caller's to see; this
     * checks that the component is in it, which is the other half — an id from
     * another workspace would otherwise export another workspace's code.
     */
    fun export(workspaceId: Long, kind: ComponentKind, id: Long, depth: ExportDepth): String {
        val root = Held(kind, id, nameOf(workspaceId, kind, id))
        val reached = if (depth == ExportDepth.DEEP) walk(workspaceId, root) else listOf(root)

        val envelope = mapper.createObjectNode()
        envelope.put("formatVersion", COMPONENT_FORMAT_VERSION)
        // Only ever quoted back in a message. Nothing branches on it, which is
        // why the version that logic reads is a separate integer above.
        envelope.put("producedBy", "Orknux $version")
        envelope.put("exportedAt", OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
        envelope.put("depth", depth.name)

        val roots = envelope.putArray("roots")
        roots.addObject().apply {
            put("kind", root.kind.name)
            put("name", root.name)
        }

        envelope.set("requires", requirements(workspaceId, reached))

        val components = envelope.putArray("components")
        // Objects before what is typed against them, and functions before the
        // conditions that call them: the file reads top-down the way it is applied.
        reached.sortedWith(compareBy({ ORDER.indexOf(it.kind) }, { it.name }))
            .forEach { components.add(describe(workspaceId, it)) }

        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(envelope)
    }

    /** `normaliseOrder.orkx.json` — recognisable, and not mistaken for source. */
    fun fileNameFor(workspaceId: Long, kind: ComponentKind, id: Long): String {
        val name = nameOf(workspaceId, kind, id)
        val safe = name.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '-' }
            .joinToString("")
            .trim('-')
            .ifEmpty { kind.label }
        return "$safe.orkx.json"
    }

    /** One component, and where in the workspace it is. */
    private data class Held(val kind: ComponentKind, val id: Long, val name: String)

    /**
     * Everything the root reaches, the root included, each thing once.
     *
     * Breadth-first with a seen set, because the graph has cycles in it — an
     * object may hold a property of its own type, which is how a tree is
     * described, and a composite condition may name another composite.
     */
    private fun walk(workspaceId: Long, root: Held): List<Held> {
        val found = LinkedHashMap<Pair<ComponentKind, Long>, Held>()
        val queue = ArrayDeque(listOf(root))
        while (queue.isNotEmpty()) {
            val held = queue.removeFirst()
            if (found.put(held.kind to held.id, held) != null) continue
            reachedBy(workspaceId, held).forEach { queue.addLast(it) }
        }
        return found.values.toList()
    }

    /** The edges: what one component points at, within this workspace. */
    private fun reachedBy(workspaceId: Long, held: Held): List<Held> = when (held.kind) {
        ComponentKind.OBJECT -> object_(workspaceId, held.id).properties
            .mapNotNull { it.refObjectId }
            .distinct()
            .map { Held(ComponentKind.OBJECT, it, nameOf(workspaceId, ComponentKind.OBJECT, it)) }

        ComponentKind.FUNCTION -> function(workspaceId, held.id).let { function ->
            (function.params.mapNotNull { it.objectId } + listOfNotNull(function.returnObjectId))
                .distinct()
                .map { Held(ComponentKind.OBJECT, it, nameOf(workspaceId, ComponentKind.OBJECT, it)) }
        }

        ComponentKind.CONDITION -> condition(workspaceId, held.id).let { condition ->
            condition.members.distinct()
                .map { Held(ComponentKind.CONDITION, it, nameOf(workspaceId, ComponentKind.CONDITION, it)) } +
                listOfNotNull(condition.functionId)
                    .map { Held(ComponentKind.FUNCTION, it, nameOf(workspaceId, ComponentKind.FUNCTION, it)) }
        }

        // Neither reaches anything: a tool is code and a skill is markdown.
        ComponentKind.TOOL, ComponentKind.SKILL -> emptyList()
    }

    /**
     * What the target workspace has to have already.
     *
     * Only variables, because only a variable cannot be carried — its value is a
     * secret, and a variable without its value is a name with nothing behind it.
     * Everything else an envelope points at but does not hold is a shallow
     * export's business, and the import works that out from the components it
     * can see rather than from a second list here that could disagree with them.
     */
    private fun requirements(workspaceId: Long, reached: List<Held>): ObjectNode {
        val node = mapper.createObjectNode()
        val listed = node.putArray("variables")
        reached.filter { it.kind == ComponentKind.FUNCTION }
            .flatMap { function(workspaceId, it.id).externals }
            .map { it.variableId }
            .distinct()
            .mapNotNull { variables.findByIdOrNull(it) }
            .filter { it.workspaceId == workspaceId }
            .sortedBy { it.name }
            .forEach { variable ->
                listed.addObject().apply {
                    put("name", variable.name)
                    put("type", variable.type.name)
                    // Says why the value is not here, without carrying anything.
                    put("secret", variable.kind.name == "SECRET")
                }
            }
        return node
    }

    private fun describe(workspaceId: Long, held: Held): ObjectNode = when (held.kind) {
        ComponentKind.OBJECT -> describeObject(object_(workspaceId, held.id))
        ComponentKind.FUNCTION -> describeFunction(workspaceId, function(workspaceId, held.id))
        ComponentKind.CONDITION -> describeCondition(workspaceId, condition(workspaceId, held.id))
        ComponentKind.TOOL -> describeTool(tool(workspaceId, held.id))
        ComponentKind.SKILL -> describeSkill(skill(workspaceId, held.id))
    }

    private fun describeObject(held: WorkflowObject): ObjectNode = start(ComponentKind.OBJECT, held.name).apply {
        put("description", held.description)
        val properties = putArray("properties")
        held.properties.forEach { property ->
            properties.addObject().apply {
                put("name", property.name)
                put("kind", property.kind.name)
                // A property can only ever point at an object, so the reference
                // is a bare name; nothing has to say of what kind.
                put("objectRef", property.refObjectId?.let { objects.findByIdOrNull(it)?.name })
                put("elementKind", property.elementKind?.name)
            }
        }
    }

    private fun describeFunction(workspaceId: Long, held: WorkflowFunction): ObjectNode =
        start(ComponentKind.FUNCTION, held.name).apply {
            put("description", held.description)
            // Both halves, always: what runs and what was written. Either alone
            // would import as a function whose editor and sandbox disagree.
            put("source", held.source)
            put("typescript", held.typescript)
            put("returnType", held.returnType.name)
            put("returnObjectRef", held.returnObjectId?.let { objects.findByIdOrNull(it)?.name })
            val params = putArray("params")
            held.params.forEach { param ->
                params.addObject().apply {
                    put("name", param.name)
                    put("type", param.type.name)
                    put("objectRef", param.objectId?.let { objects.findByIdOrNull(it)?.name })
                }
            }
            // Names only. The order matters — they are passed after the declared
            // parameters, in this order — so it is an array rather than a set.
            val handed: ArrayNode = putArray("variableRefs")
            held.externals.forEach { external ->
                variables.findByIdOrNull(external.variableId)
                    ?.takeIf { it.workspaceId == workspaceId }
                    ?.let { handed.add(it.name) }
            }
        }

    private fun describeCondition(workspaceId: Long, held: WorkflowCondition): ObjectNode =
        start(ComponentKind.CONDITION, held.name).apply {
            put("type", held.type.name)
            put("property", held.property?.name)
            put("check", held.check?.name)
            put("negate", held.negate)
            put("icon", held.icon)
            val values = putArray("values")
            held.values.forEach { values.add(it) }
            put("functionRef", held.functionId?.let { functions.findByIdOrNull(it)?.name })
            val members = putArray("memberRefs")
            held.members.forEach { member ->
                conditions.findByIdOrNull(member)?.takeIf { it.workspaceId == workspaceId }?.let { members.add(it.name) }
            }
        }

    private fun describeTool(held: AgentTool): ObjectNode = start(ComponentKind.TOOL, held.name).apply {
        put("description", held.description)
        put("source", held.source)
        put("typescript", held.typescript)
        put("enabled", held.enabled)
    }

    private fun describeSkill(held: AgentSkill): ObjectNode = start(ComponentKind.SKILL, held.name).apply {
        put("description", held.description)
        // The folder by name. Not a component of its own — a catalog holds
        // nothing but a name, so the import makes one if the target lacks it
        // rather than asking somebody to import a folder first.
        put("catalog", catalogs.findByIdOrNull(held.catalogId)?.name)
        put("content", held.content)
        put("enabled", held.enabled)
    }

    private fun start(kind: ComponentKind, name: String): ObjectNode = mapper.createObjectNode().apply {
        put("kind", kind.name)
        put("name", name)
    }

    private fun nameOf(workspaceId: Long, kind: ComponentKind, id: Long): String = when (kind) {
        ComponentKind.OBJECT -> object_(workspaceId, id).name
        ComponentKind.FUNCTION -> function(workspaceId, id).name
        ComponentKind.CONDITION -> condition(workspaceId, id).name
        ComponentKind.TOOL -> tool(workspaceId, id).name
        ComponentKind.SKILL -> skill(workspaceId, id).name
    }

    /*
     * One refusal for an id that is not there and one that belongs to another
     * workspace, for the reason `WorkspaceAccess` gives: two answers to the same
     * question is a directory of what this installation holds.
     */

    private fun object_(workspaceId: Long, id: Long): WorkflowObject =
        objects.findByIdOrNull(id)?.takeIf { it.workspaceId == workspaceId } ?: missing(ComponentKind.OBJECT, id)

    private fun function(workspaceId: Long, id: Long): WorkflowFunction =
        functions.findByIdOrNull(id)?.takeIf { it.workspaceId == workspaceId } ?: missing(ComponentKind.FUNCTION, id)

    private fun condition(workspaceId: Long, id: Long): WorkflowCondition =
        conditions.findByIdOrNull(id)?.takeIf { it.workspaceId == workspaceId } ?: missing(ComponentKind.CONDITION, id)

    private fun tool(workspaceId: Long, id: Long): AgentTool =
        tools.findByIdOrNull(id)?.takeIf { it.workspaceId == workspaceId } ?: missing(ComponentKind.TOOL, id)

    private fun skill(workspaceId: Long, id: Long): AgentSkill =
        skills.findByIdOrNull(id)?.takeIf { it.workspaceId == workspaceId } ?: missing(ComponentKind.SKILL, id)

    private fun missing(kind: ComponentKind, id: Long): Nothing =
        throw ComponentNotExportableException(kind, id)

    private companion object {
        /** Depended-on before depending: how the components are written out. */
        val ORDER = listOf(
            ComponentKind.OBJECT,
            ComponentKind.FUNCTION,
            ComponentKind.CONDITION,
            ComponentKind.TOOL,
            ComponentKind.SKILL,
        )
    }
}

/** No such component in this workspace — the same answer for both reasons. */
class ComponentNotExportableException(kind: ComponentKind, id: Long) :
    RuntimeException("This workspace has no ${kind.label} with id $id")
