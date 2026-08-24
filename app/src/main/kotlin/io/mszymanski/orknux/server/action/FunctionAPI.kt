package io.mszymanski.orknux.server.action

import io.mszymanski.orknux.server.condition.WorkflowConditionRepository
import io.mszymanski.orknux.server.dependency.ComponentDependants
import io.mszymanski.orknux.server.dependency.phrases
import io.mszymanski.orknux.server.library.ImportedLibraryView
import io.mszymanski.orknux.server.library.LibraryImports
import io.mszymanski.orknux.server.obj.ObjectNotFoundException
import io.mszymanski.orknux.server.obj.WorkflowObjectRepository
import io.mszymanski.orknux.server.plugin.PluginRepository
import io.mszymanski.orknux.server.revision.ComponentRevisionKind
import io.mszymanski.orknux.server.revision.ComponentRevisionRecorder
import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.variable.VariableNotFoundException
import io.mszymanski.orknux.server.variable.VariableType
import io.mszymanski.orknux.server.variable.WorkspaceVariableRepository
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import io.mszymanski.orknux.server.workspace.pageRequest
import io.mszymanski.orknux.workflow.script.ScriptArity
import io.mszymanski.orknux.workflow.script.ScriptResult
import io.mszymanski.orknux.workflow.script.ScriptRunner
import org.springframework.data.domain.Page
import org.springframework.data.domain.Sort
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional
import tools.jackson.core.JacksonException
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * A workspace's JavaScript functions: what an action calls when it transforms
 * something.
 *
 * The source is never run here except to be parsed — `validateFunction` backs
 * the editor's Validate — and even that happens in the sandbox, so a script
 * cannot reach anything by being compiled.
 */
@Controller
class FunctionAPI(
    private val functions: WorkflowFunctionRepository,
    private val conditions: WorkflowConditionRepository,
    private val scripts: ScriptRunner,
    private val workspaces: WorkspaceRepository,
    private val variables: WorkspaceVariableRepository,
    private val objects: WorkflowObjectRepository,
    private val access: WorkspaceAccess,
    private val auditRecorder: WorkspaceAuditRecorder,
    private val plugins: PluginRepository,
    private val revisions: ComponentRevisionRecorder,
    private val scriptImports: ScriptImports,
    private val libraryImports: LibraryImports,
    private val tools: AgentToolRepository,
    /** The one path a function is called down, shared with the workflow's own runner. */
    private val caller: FunctionCaller,
    private val mapper: ObjectMapper,
    private val dependants: ComponentDependants,
) {

    /**
     * The workspace's own functions, and the ones plugins declared.
     *
     * One query rather than two lists stitched together, so paging and ordering
     * stay true. A plugin's functions are available in every workspace, so from
     * here they are simply more functions to pick — but each one carries the plugin
     * that brought it, because "where did this come from" is the first thing
     * somebody asks about a function they did not write.
     */
    @QueryMapping
    fun workspaceFunctions(@Argument workspaceId: Long, @Argument page: Int?, @Argument size: Int?): FunctionPage {
        requireWorkspaceAccess(workspaceId)
        val found = functions.findByWorkspaceIdOrPlugin(workspaceId, pageRequest(page, size, Sort.by("name")))
        return FunctionPage(found, ::describe)
    }

    @QueryMapping
    fun function(@Argument id: Long): FunctionView? {
        val function = functions.findByIdOrNull(id)?.takeIf(::readable) ?: return null
        return describe(function)
    }

    @MutationMapping
    @Transactional
    fun createFunction(@Argument input: CreateFunctionInput): FunctionView {
        requireWorkspaceAccess(input.workspaceId)
        val name = input.name.trim()
        requireIdentifier(name) { FunctionNameInvalidException(name) }
        if (functions.findByWorkspaceIdAndName(input.workspaceId, name) != null) throw FunctionNameTakenException(name)
        // A function is called by name, so a workspace one may not shadow an
        // organisation one — the caller would have no way to say which it meant.
        if (functions.findByScopeAndName(FunctionScope.PLUGIN, name) != null) {
            throw FunctionNameTakenException(name)
        }

        val externals = input.externalVariableIds.orEmpty().toExternals(input.workspaceId)
        val externalNames = externals.map { held ->
            variables.findByIdOrNull(held.variableId)?.name ?: "external"
        }

        /*
         * Either both halves are given — a duplicate, or a client that compiled —
         * or neither is, and a new function starts from a stub written in both.
         */
        val given = input.source?.takeIf { it.isNotBlank() }
        val written = input.typescript?.takeIf { it.isNotBlank() }
        val code = when {
            given != null && written != null -> FunctionCode(javascript = given, typescript = written)
            given != null -> throw FunctionCodeIncompleteException("TypeScript this JavaScript was compiled from")
            written != null -> throw FunctionCodeIncompleteException("JavaScript compiled from this TypeScript")
            else -> starter(name, input.params.orEmpty(), externalTypes(externals, externalNames))
        }

        val source = code.javascript
        requireParses(source)
        requireSignature(source, input.params.orEmpty().size, externals.size)

        val function = functions.save(
            WorkflowFunction(
                workspaceId = input.workspaceId,
                name = name,
                description = input.description?.trim()?.ifEmpty { null },
                source = source,
                typescript = code.typescript,
                returnType = input.returnType ?: ValueType.MAP,
                returnObjectId = returnedObject(input.returnType, input.returnObjectId, input.workspaceId),
                params = input.params.orEmpty().toParams(input.workspaceId),
                externals = externals,
                // Nothing has an id yet, so nothing can import it back: a function
                // being created is the one case where a loop is not possible.
                imports = input.imports.orEmpty().toImports(input.workspaceId, importer = null),
                libraries = input.libraries.orEmpty().toLibraries(),
                lastModifiedAt = OffsetDateTime.now(),
                lastModifiedBy = currentUser(),
            ),
        )

        auditRecorder.record(input.workspaceId, WorkspaceAuditCategory.WORKFLOW, "Function $name created")
        return describe(function)
    }

    /** Backs the editor: the code, the details panel and the parameter list. */
    @MutationMapping
    @Transactional
    fun updateFunction(@Argument id: Long, @Argument input: UpdateFunctionInput): FunctionView {
        val function = functions.findByIdOrNull(id)?.takeIf(::readable) ?: throw FunctionNotFoundException(id)
        val workspaceId = requireEditable(function)

        // What it is about to stop being, kept before anything overwrites it.
        // A function has no draft, so a save is a version - the rule is the
        // recorder's, and this door only says that a save happened.
        revisions.saved(function)

        val previousName = function.name
        input.name?.trim()?.let { name ->
            requireIdentifier(name) { FunctionNameInvalidException(name) }
            if (name != function.name && functions.findByWorkspaceIdAndName(workspaceId, name) != null) {
                throw FunctionNameTakenException(name)
            }
            if (functions.findByScopeAndName(FunctionScope.PLUGIN, name) != null) {
                throw FunctionNameTakenException(name)
            }
            function.name = name
        }
        input.description?.let { function.description = it.trim().ifEmpty { null } }

        /*
         * The code changes as a pair or not at all: what runs and what it was
         * written in describe the same function, and a write that moved one of them
         * would leave the editor showing code the sandbox is not running.
         */
        val javascript = input.source
        val typescript = input.typescript
        when {
            javascript != null && typescript != null -> {
                requireParses(javascript)
                function.source = javascript
                function.typescript = typescript
            }

            javascript != null -> throw FunctionCodeIncompleteException("TypeScript this JavaScript was compiled from")
            typescript != null -> throw FunctionCodeIncompleteException("JavaScript compiled from this TypeScript")
        }
        /*
         * The return type and the object it names are one decision. Set apart, a
         * function could end up saying OBJECT while pointing at nothing, or pointing
         * at an object it no longer returns.
         */
        input.returnType?.let {
            function.returnType = it
            function.returnObjectId = returnedObject(it, input.returnObjectId, workspaceId)
        }
        input.params?.let { function.params = it.toParams(workspaceId) }
        input.externalVariableIds?.let { function.externals = it.toExternals(workspaceId) }
        input.imports?.let { function.imports = it.toImports(workspaceId, importer = id) }
        input.libraries?.let { function.libraries = it.toLibraries() }

        /*
         * Checked against what this function will be once saved, not against whichever
         * field happened to arrive: adding a parameter without touching the code breaks
         * the contract exactly as much as editing the code does.
         */
        requireSignature(function.source, function.params.size, function.externals.size)

        function.lastModifiedAt = OffsetDateTime.now()
        function.lastModifiedBy = currentUser()

        val message = if (previousName == function.name) {
            "Function ${function.name} updated"
        } else {
            "Function $previousName renamed to ${function.name}"
        }
        auditRecorder.record(workspaceId, WorkspaceAuditCategory.WORKFLOW, message)
        return describe(function)
    }

    /**
     * The editor's Validate: parses the source and says where it broke.
     *
     * It answers rather than throws, because a syntax error is what the button
     * is for, not a failed request.
     */
    @MutationMapping
    fun validateFunctionSource(@Argument workspaceId: Long, @Argument source: String): FunctionValidationView {
        requireWorkspaceAccess(workspaceId)
        val checked = scripts.validate(source)
        return FunctionValidationView(checked.valid, checked.message, checked.line, checked.column)
    }

    /**
     * Runs the function, with the arguments somebody typed, and says what came back.
     *
     * The editor's Run. It exists because Validate answers a question nobody was
     * really asking — whether the parser accepts the text — and the question
     * somebody has is whether the function does what they meant.
     *
     * Four things about it are deliberate.
     *
     * **It runs the stored function, named by id.** No source travels in this
     * mutation, so it is not a way to have the server execute something that was
     * never saved: whatever runs is a function that is already in the workspace,
     * already parsed and already signature-checked, and it is the same bytes an
     * action node would call. Running a draft would also be the wrong answer to
     * "does this work" — the thing a workflow calls is what is stored.
     *
     * **It goes through [FunctionCaller], which is what a workflow node goes
     * through.** Same sandbox, same host-resolved imports, same libraries, and the
     * workspace's variables appended by the same code — the fix #142 made on the
     * import path holds here for free, because there is no second path for it to
     * miss. A test run that resolved a grant differently would be a test of
     * something nobody ships.
     *
     * **Whoever may run it here could already run it.** The check is [readable],
     * the same one `updateFunction` makes: anybody who passes it may rewrite this
     * function's body and run it from a manual workflow run, so a Run button hands
     * out nothing that was being withheld. The arguments are the caller's; the
     * grants are not, and are never accepted from the caller.
     *
     * **It is recorded.** A run reads the workspace's variables, secrets included,
     * and this is the one execution that leaves no `workflow_execution` row behind
     * it — so without an audit entry it would be the only way to make a function
     * run that nobody could see afterwards. It is written whether the function
     * answered or threw, because it ran either way.
     */
    @MutationMapping
    fun runFunction(@Argument input: RunFunctionInput): FunctionRunView {
        access.requireVisible(input.workspaceId)
        val function = functions.findByIdOrNull(input.functionId)?.takeIf(::readable)
            ?: throw FunctionNotFoundException(input.functionId)
        /*
         * A workspace's function is run from its own workspace and nowhere else.
         * A plugin's belongs to none, and is run from whichever one is asking -
         * which is what decides the plugin settings and the permissions it is
         * given, so the workspace is asked for rather than derived.
         */
        val owner = function.workspaceId
        if (owner != null && owner != input.workspaceId) throw FunctionNotFoundException(input.functionId)

        val byName = input.arguments.orEmpty().associate { it.name to jsonOf(it) }
        // Positional, in the order the function declares them, exactly as a node
        // binds them. A parameter nobody filled in is the JSON `null`, which is
        // what a node passes for a mapping it does not have.
        val arguments = function.params.map { byName[it.name] ?: "null" }

        val result = caller.call(function, arguments, contextOf(input.workspaceId, function), input.workspaceId)

        auditRecorder.record(
            input.workspaceId,
            WorkspaceAuditCategory.WORKFLOW,
            "Function ${function.name} run from the editor",
        )

        return when (result) {
            is ScriptResult.Returned -> FunctionRunView(
                ok = true,
                returned = result.json,
                error = null,
                durationMillis = result.durationMillis.toInt(),
                settled = true,
                grants = caller.grantsOf(function),
            )

            is ScriptResult.Failed -> FunctionRunView(
                ok = false,
                returned = null,
                /*
                 * The reason is a verb phrase - "threw", "took longer than 5000 ms
                 * and was stopped" - because every other caller puts the function's
                 * own name in front of it. So does this one, since the panel showing
                 * it has room for a sentence and a bare verb reads as a fragment.
                 */
                error = "${function.name} ${result.reason}",
                durationMillis = result.durationMillis.toInt(),
                settled = result.settled,
                grants = caller.grantsOf(function),
            )
        }
    }

    /**
     * One argument as JSON, or a refusal naming the parameter.
     *
     * Two things happen here and both matter.
     *
     * It is parsed, so a value that is not JSON is refused while it still has a
     * parameter's name on it. The sandbox parses the whole argument list in one
     * go, so unchecked it would fail as a syntax error about a list the caller
     * never wrote, at a position that means nothing to them.
     *
     * And it is written back out from what was parsed, rather than passed through.
     * The arguments are joined with commas into one array, so a value carrying a
     * second one at the top level - `2, 40` - would arrive as *two* arguments and
     * push everything after it along, and what comes after the declared arguments
     * is the workspace's variables. Jackson refuses a second root-level value on
     * its own; printing the parsed tree rather than forwarding the text is what
     * makes that a property of this method instead of a property of a parser
     * setting somebody could change. Nothing reaches that list except one value
     * Jackson itself printed.
     */
    private fun jsonOf(argument: FunctionArgumentInput): String {
        val written = argument.json.trim().ifEmpty { "null" }
        val read = try {
            mapper.readTree(written)
        } catch (invalid: JacksonException) {
            throw FunctionArgumentInvalidException(argument.name, invalid.originalMessage)
        }
        return mapper.writeValueAsString(read)
    }

    /**
     * What a test run tells the script about where it is.
     *
     * The same shape a node builds, with the function's own name where a node
     * would put the action's: a script that reads `context.action` to say what
     * called it should get an honest answer rather than a blank or a lie about a
     * node that does not exist.
     */
    private fun contextOf(workspaceId: Long, function: WorkflowFunction): String = mapper.writeValueAsString(
        mapOf(
            "now" to OffsetDateTime.now().toString(),
            "timestamp" to System.currentTimeMillis(),
            "workspaceId" to workspaceId,
            "action" to function.name,
        ),
    )

    @MutationMapping
    @Transactional
    fun deleteFunction(@Argument id: Long): Boolean {
        val function = functions.findByIdOrNull(id)?.takeIf(::readable) ?: return false
        val workspaceId = requireEditable(function)

        /*
         * Anything pointing at a deleted function would have nothing to call,
         * and the caller can see what to change first.
         *
         * The webhooks are the third of these and were the one missing. A
         * webhook that authenticates with a function is not calling it to do
         * work - it is asking it whether the caller may start anything - and a
         * gatekeeper that is not there answers no. So deleting the function did
         * not break a run: it turned the webhook into a URL that refuses
         * everybody, and said so at request time, into a firing log, to nobody.
         * [PluginFunctionRegistry] has asked this question all along; the
         * workspace's own delete did not.
         */
        val callers = dependants.callersOfFunction(id)
        if (callers.isNotEmpty()) throw FunctionInUseException(function.name, callers.phrases())

        /*
         * And nothing may import it either. Said separately from the callers above
         * because the two are fixed in different places: a caller is a node somebody
         * repoints, an importer is code somebody has to open and edit. Rolling them
         * into one sentence would send half the readers to the wrong screen.
         *
         * Refused rather than left to break at run time. An import that has stopped
         * resolving is a `TypeError` in the middle of a script, which is the worst
         * possible moment and the worst possible wording to learn this in.
         */
        val importers = dependants.importersOfFunction(id)
        if (importers.isNotEmpty()) throw FunctionImportedException(function.name, importers.phrases())

        functions.delete(function)
        // Its history goes with it: the rows point at an id in a table that no
        // longer holds it, and no foreign key can say so for us.
        revisions.forget(ComponentRevisionKind.FUNCTION, id)
        auditRecorder.record(workspaceId, WorkspaceAuditCategory.WORKFLOW, "Function ${function.name} deleted")
        return true
    }

    /**
     * Whoever may see the workspace it belongs to may read it.
     *
     * An organisation function belongs to no workspace, and is readable from any
     * of them — so the check is that the caller has one at all. Anyone who can
     * reach a workspace can already list these through it.
     */
    private fun readable(function: WorkflowFunction): Boolean {
        val workspaceId = function.workspaceId
        if (workspaceId != null) return access.canSee(workspaceId)
        return access.roles().isNotEmpty()
    }

    /**
     * The workspace this function may be changed in, or a refusal.
     *
     * A function a plugin declared is externally managed: the plugin is the only
     * thing that decides what it is, by being loaded again. Refused here and not
     * merely hidden in the interface, so the rule holds for anything calling the
     * API directly.
     *
     * Visibility is settled before this is reached, by the same [readable] the
     * queries use, so that a function in a workspace the caller cannot see is
     * answered exactly as one that is not there. Asking again here would move
     * that answer back to a refusal, which is the thing that told a caller its
     * id was a real one.
     */
    private fun requireEditable(function: WorkflowFunction): Long {
        val workspaceId = function.workspaceId
        if (function.scope != FunctionScope.WORKSPACE || workspaceId == null) {
            throw FunctionExternallyManagedException(function.name)
        }
        return workspaceId
    }

    /**
     * The signature, externals included.
     *
     * Built here rather than on the entity because the entity holds variable *ids*
     * and a signature needs their names. And they belong in it: a function is handed
     * its externals as arguments after the ones it declares, so a signature that
     * leaves them out describes a call nobody makes.
     *
     * They are marked rather than merged. The two halves are filled in by different
     * people — a caller supplies the declared ones, the workspace supplies the rest —
     * and somebody reading a signature needs to know which is which.
     */
    private fun signatureOf(
        params: List<FunctionParamView>,
        externals: List<FunctionExternalView>,
    ): String {
        val declared = params.map { "${it.name}: ${named(it)}" }
        val handed = externals.map { "${it.name}: ${it.type.name.lowercase()} (external)" }
        return (declared + handed).joinToString(", ", "(", ")")
    }

    /**
     * What a parameter's type is called where somebody reads it.
     *
     * The object's own name when it names one, because "payload: SlackMessage" is the
     * useful sentence and "payload: object" is the one that sends you looking. Falls
     * back to the type when the object it named is gone, which is visible rather than
     * silent: the signature says `object` and the picker shows nothing chosen.
     */
    private fun named(param: FunctionParamView): String =
        param.objectName ?: param.type.name.lowercase()

    private fun describe(function: WorkflowFunction) = describe(
        function,
        function.params.map { param ->
            FunctionParamView(
                name = param.name,
                type = param.type,
                objectId = param.objectId,
                objectName = param.objectId?.let { objects.findByIdOrNull(it)?.name },
            )
        },
        function.externals.mapNotNull { held ->
            variables.findByIdOrNull(held.variableId)?.let { variable ->
                FunctionExternalView(
                    variableId = requireNotNull(variable.id),
                    name = variable.name,
                    type = variable.type,
                )
            }
        },
    )

    /**
     * One import, with the imported function named for the editor.
     *
     * The name is resolved here rather than left to the client, because the editor
     * needs it to annotate `imports` and the client holding an id would have to ask
     * for one function per row of a panel that is already loaded.
     */
    private fun describe(imported: ScriptImport) = ScriptImportView(
        functionId = imported.importedId,
        name = imported.importName,
        function = functions.findByIdOrNull(imported.importedId)?.let { target ->
            ImportedFunctionView(
                name = target.name,
                description = target.description,
                signature = target.signature,
                returnType = target.returnType,
                returnObjectName = target.returnObjectId?.let { objects.findByIdOrNull(it)?.name },
            )
        },
    )

    /** One library import, with the library named for the editor's annotations. */
    private fun describeLibrary(imported: ScriptImport) = ScriptLibraryImportView(
        libraryId = imported.importedId,
        name = imported.importName,
        library = libraryImports.find(imported.importedId)?.let(libraryImports::viewOf),
    )

    private fun describe(
        function: WorkflowFunction,
        params: List<FunctionParamView>,
        externals: List<FunctionExternalView>,
    ) = FunctionView(
        id = requireNotNull(function.id),
        workspaceId = function.workspaceId,
        scope = function.scope,
        editable = function.editable,
        plugin = function.pluginId?.let { id ->
            plugins.findByIdOrNull(id)?.let { FunctionPluginView(requireNotNull(it.id), it.name) }
        },
        name = function.name,
        description = function.description,
        source = function.source,
        typescript = function.typescript,
        returnType = function.returnType,
        returnObjectId = function.returnObjectId,
        returnObjectName = function.returnObjectId?.let { objects.findByIdOrNull(it)?.name },
        params = params,
        externals = externals,
        imports = function.imports.map(::describe),
        libraries = function.libraries.map(::describeLibrary),
        signature = signatureOf(params, externals),
        lastModifiedAt = function.lastModifiedAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        lastModifiedBy = function.lastModifiedBy,
    )

    /**
     * Refuses code that cannot be called the way it will be called.
     *
     * The sandbox passes arguments positionally: the declared parameters, then the
     * workspace's variables. So a function whose code takes two arguments while its
     * details declare three is not a function that works — it will be handed a value
     * it never reads, or read one it was never handed, and only find out mid-run.
     *
     * Checked when it is saved, which is the last moment somebody is looking at it.
     */
    private fun requireSignature(source: String, params: Int, externals: Int) {
        val expected = params + externals
        when (val counted = scripts.arity(source)) {
            is ScriptArity.Counted ->
                if (counted.parameters != expected) {
                    throw FunctionSignatureMismatchException(counted.parameters, params, externals)
                }

            // No default export, or not a function: it could never have run.
            is ScriptArity.Unreadable -> throw FunctionSourceInvalidException(counted.reason)
        }
    }

    private fun requireParses(source: String) {
        val checked = scripts.validate(source)
        if (!checked.valid) throw FunctionSourceInvalidException(checked.message ?: "The script could not be parsed")
    }

    /**
     * What a new function starts as: something that runs, and that returns what
     * it was given rather than an empty object, so the first run of a workflow
     * shows the shape reaching it.
     *
     * Written twice, from the same list, in one place. The TypeScript is what
     * somebody opens; the JavaScript is what runs until the first save replaces it
     * with a real compile. They are not two versions that could drift — they are one
     * stub printed with the annotations and without them, and the only difference
     * between them is the annotations.
     *
     * Only the parameters are annotated. A return type would be a second thing to
     * keep in step with the details panel, which rewrites parameter lists and knows
     * nothing about return positions — so it would be stale the first time somebody
     * changed the return type in the panel.
     */
    private fun starter(
        name: String,
        params: List<FunctionParamInput>,
        externals: List<Pair<String, VariableType>>,
    ): FunctionCode {
        /*
         * Both kinds of parameter, in the order they arrive: the declared ones, then
         * the workspace's. A stub that took only the declared ones would be a stub
         * the signature check refuses the moment it is created.
         */
        val names = params.map { it.name } + externals.map { it.first }
        val annotated = params.map { "${it.name}: ${typeScriptType(it.type, objectNameOf(it.objectId))}" } +
            externals.map { "${it.first}: ${typeScriptType(it.second)}" }

        /*
         * Only the declared ones come back out. An external is a workspace value —
         * often a secret — and a stub that returned it would hand it to the next node
         * by default, which is not a default anybody chose.
         */
        val returned = when {
            params.isEmpty() -> "{}"
            else -> params.joinToString(", ", "{ ", " }") { it.name }
        }

        fun body(arguments: String) = """
            export default async function $name($arguments) {
              // What this returns is handed to the next node.
              return $returned;
            }
        """.trimIndent()

        return FunctionCode(javascript = body(names.joinToString(", ")), typescript = body(annotated.joinToString(", ")))
    }

    /** The variables a stub is handed, with the types its annotations need. */
    private fun externalTypes(
        externals: List<FunctionExternal>,
        names: List<String>,
    ): List<Pair<String, VariableType>> = externals.mapIndexed { at, held ->
        val name = names.getOrElse(at) { "external" }
        name to (variables.findByIdOrNull(held.variableId)?.type ?: VariableType.STRING)
    }

    /**
     * The variables this function is to be handed, checked against the workspace
     * that owns it: a function cannot be given another workspace's secret by id.
     */
    private fun List<Long>.toExternals(workspaceId: Long): MutableList<FunctionExternal> = distinct()
        .map { variableId ->
            val variable = variables.findByIdOrNull(variableId) ?: throw VariableNotFoundException(variableId)
            if (variable.workspaceId != workspaceId) throw VariableNotFoundException(variableId)
            FunctionExternal(variableId = variableId)
        }
        .toMutableList()

    /**
     * The functions this one is to import, checked before they are stored.
     *
     * Four refusals, and they are different questions. A name that is not an
     * identifier could not be written in the code. Two imports under one name are
     * one the code can reach and one it cannot. A function in another workspace, or
     * one a plugin declared, is not a function this one may reach at all. And an
     * import that closes a loop is a run that never finishes being assembled — so
     * it is refused here, while somebody is looking at it, rather than found by the
     * walk at the moment a workflow needed it.
     */
    private fun List<ScriptImportInput>.toImports(workspaceId: Long, importer: Long?): MutableList<ScriptImport> {
        val taken = mutableSetOf<String>()
        return map { asked ->
            val name = asked.name.trim()
            requireIdentifier(name) { ImportNameInvalidException(name) }
            if (!taken.add(name)) throw ImportNameTakenException(name)

            val imported = functions.findByIdOrNull(asked.functionId) ?: throw ImportNotFoundException(asked.functionId)
            scriptImports.requireImportable(imported, workspaceId, importer)
            ScriptImport(importedId = asked.functionId, importName = name)
        }.toMutableList()
    }

    /**
     * The libraries this script is to import, checked before they are stored.
     *
     * The same two questions an imported function is asked about its name, and one
     * fewer about itself: every loaded library is offerable to every workspace, so
     * there is nothing to compare a workspace against, and a library imports
     * nothing, so there is no loop to look for.
     */
    private fun List<ScriptLibraryImportInput>.toLibraries(): MutableList<ScriptImport> {
        val taken = mutableSetOf<String>()
        return map { asked ->
            val name = asked.name.trim()
            requireIdentifier(name) { ImportNameInvalidException(name) }
            if (!taken.add(name)) throw ImportNameTakenException(name)

            libraryImports.require(asked.libraryId)
            ScriptImport(importedId = asked.libraryId, importName = name)
        }.toMutableList()
    }

    private fun List<FunctionParamInput>.toParams(workspaceId: Long): MutableList<FunctionParam> = map { param ->
        val name = param.name.trim()
        requireIdentifier(name) { FunctionParamInvalidException(name) }
        FunctionParam(
            name = name,
            type = param.type,
            // Only an object parameter keeps one. Anything else is cleared rather
            // than carried: a stale id on a parameter somebody changed to a string
            // is a thing that comes back the day the type changes again.
            objectId = if (param.type == ValueType.OBJECT) {
                requireObject(param.objectId, workspaceId) { FunctionObjectRequiredException(name) }
            } else {
                null
            },
        )
    }.toMutableList()

    /**
     * The object a return type names, or null when it names none.
     *
     * A function returning a defined shape is worth as much as one taking it: the
     * node downstream knows what fields it can point at, and the editor can annotate
     * what the code has to produce.
     */
    private fun returnedObject(type: ValueType?, objectId: Long?, workspaceId: Long): Long? =
        if (type == ValueType.OBJECT) {
            requireObject(objectId, workspaceId) { FunctionObjectRequiredException("the return type") }
        } else {
            null
        }

    /**
     * The object a declaration points at, checked against the workspace that owns it.
     *
     * Two refusals, not one. Nothing chosen is somebody who has not finished — say so
     * and name the alternative. Something chosen that belongs elsewhere is a workspace
     * reaching into another's definitions by id, which is not a mistake to explain
     * helpfully; it is answered as though the object does not exist, because from
     * where the caller stands it does not.
     */
    private fun requireObject(objectId: Long?, workspaceId: Long, missing: () -> RuntimeException): Long {
        val id = objectId ?: throw missing()
        val found = objects.findByIdOrNull(id) ?: throw ObjectNotFoundException(id)
        if (found.workspaceId != workspaceId) throw ObjectNotFoundException(id)
        return id
    }

    private fun requireIdentifier(name: String, failure: () -> RuntimeException) {
        if (!IDENTIFIER.matches(name)) throw failure()
    }

    private fun currentUser(): String =
        SecurityContextHolder.getContext().authentication?.name ?: "system"

    private fun requireWorkspaceAccess(workspaceId: Long) {
        access.requireVisible(workspaceId)
    }

    /** What an object is called, for an annotation that has to name it. */
    private fun objectNameOf(objectId: Long?): String? = objectId?.let { objects.findByIdOrNull(it)?.name }

    /** A variable's shape, written the way an annotation needs it. */
    private fun typeScriptType(type: VariableType): String = when (type) {
        VariableType.STRING -> "string"
        VariableType.NUMBER -> "number"
        VariableType.BOOLEAN -> "boolean"
    }

    private companion object {
        /** A name JavaScript can call: what the source is written against. */
        val IDENTIFIER = Regex("[A-Za-z_$][A-Za-z0-9_$]{0,63}")
    }
}

/**
 * One function's code, in both languages.
 *
 * They travel together because they are saved together: the JavaScript is what the
 * sandbox runs, the TypeScript is what the editor opens, and neither can be
 * recovered from the other.
 */
data class FunctionCode(val javascript: String, val typescript: String)

data class FunctionParamInput(
    val name: String,
    val type: ValueType,
    /** Which of the workspace's objects, when the type is OBJECT. */
    val objectId: Long? = null,
)

data class CreateFunctionInput(
    val workspaceId: Long,
    val name: String,
    val description: String? = null,
    /**
     * The compiled JavaScript. Left out for a new function, which starts from a
     * stub that runs; given only together with the [typescript] it came from.
     */
    val source: String? = null,
    /** The TypeScript [source] was compiled from. The two arrive together. */
    val typescript: String? = null,
    val returnType: ValueType? = null,
    /** Which object it returns, when the return type is OBJECT. */
    val returnObjectId: Long? = null,
    val params: List<FunctionParamInput>? = null,
    /** Which of the workspace's variables it is handed, in order. */
    val externalVariableIds: List<Long>? = null,
    /** The workspace's other functions it calls, under the names it calls them. */
    val imports: List<ScriptImportInput>? = null,
    /** The installation's libraries it uses, under the names it uses them by. */
    val libraries: List<ScriptLibraryImportInput>? = null,
)

data class UpdateFunctionInput(
    val name: String? = null,
    val description: String? = null,
    /** The compiled JavaScript; sent with the [typescript] it was compiled from. */
    val source: String? = null,
    /** The TypeScript [source] was compiled from. Neither moves without the other. */
    val typescript: String? = null,
    val returnType: ValueType? = null,
    /** Which object it returns, when the return type is OBJECT. */
    val returnObjectId: Long? = null,
    val params: List<FunctionParamInput>? = null,
    /** Null leaves them alone; an empty list takes them all off. */
    val externalVariableIds: List<Long>? = null,
    /** Null leaves them alone; an empty list takes them all off. */
    val imports: List<ScriptImportInput>? = null,
    /** Null leaves them alone; an empty list takes them all off. */
    val libraries: List<ScriptLibraryImportInput>? = null,
)

data class FunctionParamView(
    val name: String,
    val type: ValueType,
    val objectId: Long?,
    /**
     * What that object is called, resolved here.
     *
     * The editor annotates parameters with this and declares an interface of the same
     * name, so it needs the name rather than the id — and looking it up per parameter
     * on the client would be one request per row for something already loaded here.
     */
    val objectName: String?,
)

/** A variable the function is handed, as the editor shows it. */
data class FunctionExternalView(
    val variableId: Long,
    val name: String,
    val type: VariableType,
)

data class FunctionView(
    val id: Long,
    /** Null for a plugin's function: it belongs to no single workspace. */
    val workspaceId: Long?,
    /** WORKSPACE or PLUGIN — where it came from, said outright. */
    val scope: FunctionScope,
    /** False for anything a plugin declared, which is what the editor reads. */
    val editable: Boolean,
    /**
     * Which plugin brought it, for anything a plugin declared.
     *
     * Named here rather than left to the caller to look up, because listing plugins
     * is an administrator's query and the people picking functions are not
     * administrators. A picker has to be able to say "from the Teammates plugin"
     * without being allowed to see the plugin list.
     */
    val plugin: FunctionPluginView?,
    val name: String,
    val description: String?,
    val source: String,
    /** What it was written in, or null for a plugin's function. */
    val typescript: String?,
    val returnType: ValueType,
    /** Which object it returns, when it returns one. */
    val returnObjectId: Long?,
    val returnObjectName: String?,
    val params: List<FunctionParamView>,
    /** The workspace's variables it is handed, after the parameters it declares. */
    val externals: List<FunctionExternalView>,
    /** What it imports, and what it calls each of them. */
    val imports: List<ScriptImportView>,
    /** The libraries it uses, and what it calls each of them. */
    val libraries: List<ScriptLibraryImportView>,
    /** "(input: object, format: string)", ready for the list. */
    val signature: String,
    val lastModifiedAt: String,
    val lastModifiedBy: String,
)

/**
 * The plugin a function came from.
 *
 * Just enough to name it and link to it. Deliberately not the plugin itself: the
 * people picking functions are not administrators, and what they need is the name
 * of the thing that brought it, not everything about what was loaded.
 */
data class FunctionPluginView(val id: Long, val name: String)

/**
 * One thing a script imports.
 *
 * The id is the reference and the name is the importer's word for it, which is why
 * both are here and why neither can be derived from the other. Shared between the
 * function editor and the tool editor: a tool importing a function is the same
 * arrangement, and two inputs saying the same thing would drift.
 */
data class ScriptImportInput(val functionId: Long, val name: String)

/** One import, as the editor draws it: the reference, the local name, and what it is. */
data class ScriptImportView(
    val functionId: Long,
    /** What the code calls it. Unchanged by anything happening to the function. */
    val name: String,
    /** Null only if the function went away behind the delete guard's back. */
    val function: ImportedFunctionView?,
)

/**
 * What an imported function is, as far as the importer needs to know.
 *
 * Enough to annotate `imports` in the editor and to say in a panel what was
 * imported. Not the source: whoever is reading this is writing a call, not the
 * callee, and the callee has its own screen.
 */
data class ImportedFunctionView(
    val name: String,
    val description: String?,
    val signature: String,
    val returnType: ValueType,
    val returnObjectName: String?,
)

/**
 * One library a script imports.
 *
 * The same two halves an imported function has, and apart for the same reason:
 * the id is the reference, the name is the importer's own word for it, and a
 * library replaced under the same key does not disturb either.
 */
data class ScriptLibraryImportInput(val libraryId: Long, val name: String)

/** One library import, as the editor draws it. */
data class ScriptLibraryImportView(
    val libraryId: Long,
    val name: String,
    /** Null only if the library went away behind the delete guard's back. */
    val library: ImportedLibraryView?,
)

data class FunctionValidationView(
    val valid: Boolean,
    val message: String?,
    val line: Int?,
    val column: Int?,
)

/**
 * One argument for a test run: which parameter, and the value as JSON.
 *
 * JSON rather than text, because that is what crosses into the sandbox and
 * because a parameter's type is not always a scalar - a map or an array has no
 * spelling as a plain string. The editor writes it from the control it drew, so
 * a string field sends `"kraków"` and a number field sends `3`.
 */
data class FunctionArgumentInput(val name: String, val json: String)

data class RunFunctionInput(
    /**
     * Which workspace is asking.
     *
     * Not derived from the function, because a plugin's function belongs to none
     * and its settings and permissions are per workspace. For a workspace's own it
     * has to be the workspace that owns it.
     */
    val workspaceId: Long,
    val functionId: Long,
    /**
     * What to pass, by parameter name. A name the function does not declare is
     * ignored and a parameter nobody filled in arrives as `null`, which is what a
     * node passes for a mapping it does not have.
     */
    val arguments: List<FunctionArgumentInput>? = null,
)

/**
 * What one test run came to.
 *
 * Deliberately not two shapes. Whether a run answered or failed is a field on one
 * answer rather than a union, because the panel showing it has the same job either
 * way: say how long it took, and show what came back.
 */
data class FunctionRunView(
    val ok: Boolean,
    /** The JSON it returned. Null when it failed, and also when it returned nothing. */
    val returned: String?,
    /** What went wrong, with the function's name in front of it. Null when it answered. */
    val error: String?,
    val durationMillis: Int,
    /**
     * Whether asking again could ever answer differently.
     *
     * False is the interesting one: it means the run was stopped by the clock or by
     * how busy the machine was rather than by anything about the code, so the panel
     * can say to try again instead of sending somebody to read a function that is
     * fine.
     */
    val settled: Boolean,
    /**
     * The workspace variables it was handed, by name.
     *
     * Names only, and never values - the variables page does not show them either.
     * They are here so that a run can be seen to have resolved them: a function
     * that reads a secret and answers wrongly looks the same as one that was handed
     * nothing, and this is the difference.
     */
    val grants: List<String>,
)

data class FunctionPage(
    val content: List<FunctionView>,
    val page: Int,
    val size: Int,
    val totalElements: Int,
    val totalPages: Int,
) {
    constructor(page: Page<WorkflowFunction>, describe: (WorkflowFunction) -> FunctionView) : this(
        content = page.content.map(describe),
        page = page.number,
        size = page.size,
        totalElements = page.totalElements.toInt(),
        totalPages = page.totalPages,
    )
}
