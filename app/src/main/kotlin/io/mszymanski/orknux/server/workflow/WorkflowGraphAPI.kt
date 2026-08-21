package io.mszymanski.orknux.server.workflow

import io.mszymanski.orknux.server.action.ActionParameters
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.obj.WorkflowObjectRepository
import io.mszymanski.orknux.server.revision.ComponentRevisionKind
import io.mszymanski.orknux.server.revision.ComponentRevisionRecorder
import io.mszymanski.orknux.server.revision.RevisionSubject
import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import io.mszymanski.orknux.server.action.ActionParamView
import io.mszymanski.orknux.server.action.WorkflowActionRepository
import io.mszymanski.orknux.server.condition.WorkflowConditionRepository
import io.mszymanski.orknux.server.trigger.WorkflowTriggerRepository
import io.mszymanski.orknux.workflow.execution.WorkflowGraph as RunnableGraph
import org.springframework.data.domain.PageRequest
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.stereotype.Controller
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.format.DateTimeFormatter

@Controller
class WorkflowGraphAPI(
    private val workflows: WorkflowRepository,
    private val assignments: WorkspaceWorkflowRepository,
    private val nodes: WorkflowNodeRepository,
    private val edges: WorkflowEdgeRepository,
    private val triggers: WorkflowTriggerRepository,
    private val actions: WorkflowActionRepository,
    private val conditions: WorkflowConditionRepository,
    private val workspaces: WorkspaceRepository,
    private val validator: GraphValidator,
    private val parameters: ActionParameters,
    private val agents: AgentRepository,
    private val objects: WorkflowObjectRepository,
    private val access: WorkspaceAccess,
    private val auditRecorder: WorkspaceAuditRecorder,
    private val publications: WorkflowPublicationRepository,
    private val revisions: ComponentRevisionRecorder,
    private val graphSource: AppWorkflowGraphSource,
    private val mapper: ObjectMapper,
) {

    @QueryMapping
    fun workflowGraph(@Argument workspaceId: Long, @Argument workflowId: Long): WorkflowGraphView {
        requireAssignment(workspaceId, workflowId)
        return graphOf(workspaceId, workflowId)
    }

    /**
     * What a node would pass if it were pointed at this action right now.
     *
     * The editor asks when somebody picks an action, so the panel can show the
     * parameters that action has instead of an empty box. It is a suggestion:
     * what the node keeps is whatever is saved on it.
     */
    @QueryMapping
    fun actionParameterDefaults(@Argument workspaceId: Long, @Argument actionId: Long): List<NodeMappingView> {
        val workspace = access.requireVisible(workspaceId)
        val action = actions.findByIdOrNull(actionId) ?: throw ActionNotInCatalogueException(actionId)
        if (action.workspaceId != workspaceId) throw ActionNotInCatalogueException(actionId)
        return parameters.defaultsFor(action).map { NodeMappingView(it.name, it.expression, it.mode) }
    }

    /**
     * The same answer a save gives, for a graph that has not been saved.
     *
     * What a node needs and produces follows from what it points at and what it
     * was told to pass, so it changes the moment either does — but it was only
     * ever worked out when the graph was written down, which left the editor
     * showing the ports and the problems of the graph as it was some edits ago.
     *
     * Nothing is written and nothing is refused: this is what the graph would be,
     * asked of a graph somebody is still drawing.
     */
    @QueryMapping
    fun workflowGraphPreview(
        @Argument workspaceId: Long,
        @Argument workflowId: Long,
        @Argument input: WorkflowGraphInput,
    ): WorkflowGraphView {
        requireAssignment(workspaceId, workflowId)
        val workflow = workflows.findByIdOrNull(workflowId) ?: throw WorkflowNotFoundException(workflowId)

        val known = input.nodes.map { it.key }.toSet()
        val proposed = input.nodes.map { nodeOf(workflowId, it, refusing = false) }
        // An edge to a node that is not there is something a half-drawn graph
        // has; the validator is given the graph, not an argument about it.
        val drawn = input.edges
            .filter { it.source in known && it.target in known }
            .map { WorkflowEdge(workflowId = workflowId, sourceKey = it.source, targetKey = it.target, branch = it.branch) }

        return WorkflowGraphView(
            workflowId = workflowId,
            name = workflow.name,
            description = workflow.description,
            status = workflow.status,
            enabled = enabledIn(workspaceId, workflowId),
            nodes = proposed.map { node ->
                val ports = validator.portsOf(node)
                WorkflowNodeView(node, ports.inputs, ports.outputs)
            },
            edges = drawn.map(::WorkflowEdgeView),
            problems = validator.problems(proposed, drawn),
        )
    }

    /** Replaces the whole graph: the editor always sends the full picture. */
    @MutationMapping
    @Transactional
    fun saveWorkflowGraph(
        @Argument workspaceId: Long,
        @Argument workflowId: Long,
        @Argument input: WorkflowGraphInput,
    ): WorkflowGraphView {
        requireAssignment(workspaceId, workflowId)

        val keys = input.nodes.map { it.key }
        require(keys.size == keys.toSet().size) { "Node keys have to be unique within a workflow" }
        input.edges.forEach { edge ->
            require(edge.source in keys && edge.target in keys) {
                "Edge ${edge.source} -> ${edge.target} refers to a node that is not in the graph"
            }
        }

        input.nodes.forEach { requireTriggerBelongsToWorkspace(workspaceId, it) }
        input.nodes.forEach { requireActionBelongsToWorkspace(workspaceId, it) }
        input.nodes.forEach { requireConditionBelongsToWorkspace(workspaceId, it) }
        input.nodes.forEach { requireAgentBelongsToWorkspace(workspaceId, it) }
        input.nodes.forEach { requireObjectBelongsToWorkspace(workspaceId, it) }

        // A graph is drawn before it is finished, so only the shapes that could
        // never run are refused; everything else comes back as advice.
        val proposed = input.nodes.map { node ->
            WorkflowNode(
                workflowId = workflowId,
                nodeKey = node.key,
                kind = node.kind,
                name = node.name,
                // Carried into the check because two nodes claiming one output
                // name is one of the shapes a save refuses; without it here the
                // rule would only ever be evaluated against a blank.
                outputName = node.outputName?.trim()?.ifEmpty { null },
                orientation = node.orientation,
                // Carried into the check for the same reason: a failure edge
                // out of a node that does not handle failure is one of the
                // shapes a save refuses, and without this it would be judged
                // against a node that never handles it.
                fallbackEnabled = node.fallbackEnabled && handlesFailure(node.kind),
                positionX = node.x,
                positionY = node.y,
            )
        }
        val proposedEdges = input.edges.map {
            WorkflowEdge(workflowId = workflowId, sourceKey = it.source, targetKey = it.target, branch = it.branch)
        }
        val refusals = validator.problems(proposed, proposedEdges, hardOnly = true)
        if (refusals.isNotEmpty()) throw GraphInvalidException(refusals)

        nodes.deleteByWorkflowId(workflowId)
        edges.deleteByWorkflowId(workflowId)
        nodes.flush()
        edges.flush()

        nodes.saveAll(input.nodes.map { nodeOf(workflowId, it) })
        edges.saveAll(
            input.edges.map { edge ->
                WorkflowEdge(workflowId = workflowId, sourceKey = edge.source, targetKey = edge.target, branch = edge.branch)
            },
        )

        // Editing puts a published workflow back into draft.
        val workflow = workflows.findByIdOrNull(workflowId) ?: throw WorkflowNotFoundException(workflowId)
        workflow.status = WorkflowStatus.DRAFT

        /*
         * Said, and deliberately not decided here.
         *
         * A workflow has a draft, so by the rule the recorder holds this writes
         * nothing - a draft is a draft, and the version is what gets published.
         * The call is here anyway because the rule is one rule: if a workflow's
         * kind ever stopped having a draft, or another kind gained one, the
         * recorder would change its answer and this door would already be
         * right. Nothing is serialised while the answer is no.
         */
        revisions.saved(ComponentRevisionKind.WORKFLOW) {
            RevisionSubject(
                workspaceId = workspaceId,
                componentId = workflowId,
                name = workflow.name,
                savedAt = java.time.OffsetDateTime.now(),
                savedBy = currentUser(),
                snapshot = WorkflowSnapshot.write(graphSource.drafted(workflowId, workflow.name), mapper),
            )
        }

        auditRecorder.record(workspaceId, WorkspaceAuditCategory.WORKFLOW, "Workflow ${workflow.name} graph updated")
        return graphOf(workspaceId, workflowId)
    }

    @MutationMapping
    @Transactional
    fun publishWorkflow(@Argument workspaceId: Long, @Argument workflowId: Long): WorkflowGraphView {
        requireAssignment(workspaceId, workflowId)

        val workflow = workflows.findByIdOrNull(workflowId) ?: throw WorkflowNotFoundException(workflowId)
        if (nodes.findByWorkflowId(workflowId).isEmpty()) throw WorkflowGraphEmptyException()

        /*
         * Publishing is the copy, not the badge.
         *
         * The status is what a person reads; this is what a trigger runs. They
         * are written together so that a graph can never be marked live without
         * something live to point at - and from here, editing and saving change
         * the draft alone, which is what makes it safe to leave one half-drawn.
         */
        revisions.published(
            workflowId = workflowId,
            graph = WorkflowSnapshot.write(graphSource.drafted(workflowId, workflow.name), mapper),
            by = currentUser(),
        )
        workflow.status = WorkflowStatus.PUBLISHED
        auditRecorder.record(workspaceId, WorkspaceAuditCategory.WORKFLOW, "Workflow ${workflow.name} published")
        return graphOf(workspaceId, workflowId)
    }

    /**
     * Every publication of this workflow, newest first — its version history.
     *
     * Without the graphs. A publication's snapshot is the whole runnable graph
     * and a workflow published daily for a month is a month of them; the list
     * says who published what and when, and [workflowPublicationGraph] fetches
     * one when somebody opens it.
     */
    @QueryMapping
    @Transactional(readOnly = true)
    fun workflowPublications(
        @Argument workspaceId: Long,
        @Argument workflowId: Long,
        @Argument limit: Int?,
    ): List<WorkflowPublicationView> {
        requireAssignment(workspaceId, workflowId)
        val held = publications.findByWorkflowIdOrderByIdDesc(
            workflowId,
            PageRequest.of(0, (limit ?: DEFAULT_PUBLICATIONS).coerceIn(1, MOST_PUBLICATIONS)),
        )
        // The first row of a list ordered newest-first is what runs, by the
        // same rule the runner uses - so the two cannot disagree about which.
        val running = held.firstOrNull()?.id
        return held.map { WorkflowPublicationView(it, current = it.id == running) }
    }

    /** One publication's graph, as it was published. */
    @QueryMapping
    @Transactional(readOnly = true)
    fun workflowPublicationGraph(@Argument workspaceId: Long, @Argument publicationId: Long): String {
        val held = publicationIn(workspaceId, publicationId)
        return held.graph
    }

    /**
     * Puts an older publication back into service.
     *
     * By publishing it again, rather than by deleting what came after: the
     * newest publication is what runs, so a restore that removed rows would be
     * a history that rewrites itself, and there would be no record that
     * somebody rolled anything back. This is the shape of reverting a commit —
     * a new entry, saying which older one it copied.
     *
     * **It does not touch the draft.** The draft is what somebody is in the
     * middle of and it is not versioned, so overwriting it with a month-old
     * graph would destroy unpublished work with nothing to get it back from.
     * What changes is what runs. The badge follows from that: it says Published
     * when the draft and the restored graph are the same picture, and Draft
     * when they are not, which is exactly what it has always meant.
     */
    @MutationMapping
    @Transactional
    fun restoreWorkflowPublication(
        @Argument workspaceId: Long,
        @Argument publicationId: Long,
    ): WorkflowGraphView {
        val held = publicationIn(workspaceId, publicationId)
        val workflowId = held.workflowId
        val workflow = workflows.findByIdOrNull(workflowId) ?: throw WorkflowNotFoundException(workflowId)

        revisions.published(
            workflowId = workflowId,
            graph = held.graph,
            by = currentUser(),
            restoredFrom = publicationId,
        )
        workflow.status = if (samePicture(held.graph, graphSource.drafted(workflowId, workflow.name))) {
            WorkflowStatus.PUBLISHED
        } else {
            WorkflowStatus.DRAFT
        }
        auditRecorder.record(
            workspaceId,
            WorkspaceAuditCategory.WORKFLOW,
            "Workflow ${workflow.name} restored to the publication of " +
                DateTimeFormatter.ISO_INSTANT.format(held.publishedAt.toInstant()),
        )
        return graphOf(workspaceId, workflowId)
    }

    /**
     * Whether a stored graph and a drawn one are the same picture.
     *
     * Compared as parsed trees rather than as text, because the stored half has
     * been through a `jsonb` column: Postgres rewrites the object it was given -
     * key order and whitespace are its own - so two identical graphs come back
     * as two different strings, and the badge would say Draft for every restore.
     *
     * Nodes keep the order the draft is read in, so two graphs that differ only
     * in that read as different and the badge says Draft. That is the safe way
     * round: it asks somebody to publish a graph that already is, rather than
     * telling them one is live when it is not.
     */
    private fun samePicture(stored: String, drawn: RunnableGraph): Boolean =
        mapper.readTree(stored) == mapper.readTree(WorkflowSnapshot.write(drawn, mapper))

    /** One publication, checked to be this workspace's before it is handed out. */
    private fun publicationIn(workspaceId: Long, publicationId: Long): WorkflowPublication {
        val held = publications.findByIdOrNull(publicationId)
            ?: throw WorkflowPublicationNotFoundException(publicationId)
        requireAssignment(workspaceId, held.workflowId)
        return held
    }

    /** Whoever is asking, for the record of who made a graph live. */
    private fun currentUser(): String =
        SecurityContextHolder.getContext().authentication?.name ?: "system"

    private fun graphOf(workspaceId: Long, workflowId: Long): WorkflowGraphView {
        val workflow = workflows.findByIdOrNull(workflowId) ?: throw WorkflowNotFoundException(workflowId)
        val held = nodes.findByWorkflowId(workflowId)
        val drawn = edges.findByWorkflowId(workflowId)
        return WorkflowGraphView(
            workflowId = workflowId,
            name = workflow.name,
            description = workflow.description,
            status = workflow.status,
            enabled = enabledIn(workspaceId, workflowId),
            nodes = held.map { node ->
                val ports = validator.portsOf(node)
                WorkflowNodeView(node, ports.inputs, ports.outputs)
            },
            edges = drawn.map(::WorkflowEdgeView),
            problems = validator.problems(held, drawn),
        )
    }

    /**
     * What a session node is: a key, and what it is filed under.
     *
     * In this order because that is the order they read in - the prefix names
     * the conversation, the key names which one of it. Both are always present,
     * and the optional one is the prefix.
     */
    private val SESSION_PARAMETERS = listOf("sessionKeyPrefix", "sessionKey")

    /**
     * The node a save would write, which is also the node a preview describes.
     *
     * One place, so what the editor is shown and what it gets when it saves
     * cannot be two different nodes.
     *
     * @param refusing whether a setting that could never work stops the caller.
     *   A save refuses; a preview is asked about a graph somebody is still
     *   typing into, where refusing would be arguing with them mid-word.
     */
    private fun nodeOf(workflowId: Long, node: WorkflowNodeInput, refusing: Boolean = true) = WorkflowNode(
        workflowId = workflowId,
        nodeKey = node.key,
        kind = node.kind,
        name = node.name.trim().ifEmpty { "Untitled node" },
        description = node.description?.trim()?.ifEmpty { null },
        agentId = node.agentId.takeIf { node.kind == NodeKind.AGENT },
        triggerId = node.triggerId.takeIf { node.kind == NodeKind.TRIGGER },
        actionId = node.actionId.takeIf { node.kind == NodeKind.ACTION },
        conditionId = node.conditionId.takeIf { node.kind == NodeKind.CONDITION },
        objectId = node.objectId.takeIf { node.kind == NodeKind.OBJECT },
        outputName = node.outputName?.trim()?.ifEmpty { null }
            // Only a node that produces something can name it; a trigger names
            // its own fields and a condition passes through what it was given.
            ?.takeIf {
                node.kind == NodeKind.AGENT || node.kind == NodeKind.ACTION || node.kind == NodeKind.OBJECT
            }
            ?.also { if (refusing) requireReferenceable(it) },
        icon = node.icon?.trim()?.ifEmpty { null },
        orientation = node.orientation,
        positionX = node.x,
        positionY = node.y,
        // Only a node with two ways out has them to name: a condition, or a
        // node that handles its own failure.
        yesLabel = node.yesLabel?.trim()?.ifEmpty { null }?.takeIf { forks(node) },
        noLabel = node.noLabel?.trim()?.ifEmpty { null }?.takeIf { forks(node) },
        fallbackEnabled = node.fallbackEnabled && handlesFailure(node.kind),
        retryAttempts = node.retryAttempts?.coerceIn(MIN_ATTEMPTS, MAX_ATTEMPTS)
            ?.takeIf { it > MIN_ATTEMPTS && handlesFailure(node.kind) },
        retryBackoffSeconds = node.retryBackoffSeconds?.coerceIn(0, MAX_BACKOFF_SECONDS)
            ?.takeIf { handlesFailure(node.kind) },
        // Kept only where the wait it shapes is kept, and only where there is a
        // second attempt for it to sit between: a curve on a node that runs once
        // is a setting that describes nothing, and one saved on a kind that
        // cannot retry is a setting nothing will ever read.
        retryBackoff = node.retryBackoff?.takeIf {
            handlesFailure(node.kind) && (node.retryAttempts ?: MIN_ATTEMPTS) > MIN_ATTEMPTS
        },
        mappings = mappingsFor(node, refusing),
    )

    /**
     * Whether a failure here is the node's own business rather than the run's.
     *
     * An action calls something outside this installation, and an agent calls a
     * model, which is the same bet with a longer wait and a bill attached: both
     * fail for reasons that have nothing to do with the graph, and both fail in
     * ways a second go or a different edge is the honest answer to. The rest
     * cannot. A condition that does not hold has answered, not failed; a
     * trigger is what started the run; an object node assembles what it was
     * already handed, so a second attempt assembles the same thing.
     */
    private fun handlesFailure(kind: NodeKind): Boolean =
        kind == NodeKind.ACTION || kind == NodeKind.AGENT

    /** Whether this node has two ways out, and so two labels worth keeping. */
    private fun forks(node: WorkflowNodeInput): Boolean =
        node.kind == NodeKind.CONDITION || (handlesFailure(node.kind) && node.fallbackEnabled)

    /**
     * What this node will pass, resolved against the action it points at.
     *
     * Taken from what was sent where it names a parameter the action actually
     * has, and seeded from the action otherwise. Resolving it this way keeps a
     * node honest when its action changes underneath it: parameters the new
     * action does not have are dropped, ones it gained arrive with the action's
     * own suggestion, and nothing has to remember to tidy up.
     *
     * The action is read here and nowhere else. Once a node holds its mappings
     * they are what runs, so editing them cannot reach back into a definition
     * other nodes are using.
     */
    private fun mappingsFor(node: WorkflowNodeInput, refusing: Boolean): MutableList<NodeMapping> {
        val sent = node.mappings.orEmpty().associateBy { it.name }

        /*
         * An agent's parameters are the node's own.
         *
         * There is no definition to seed them from — an agent declares no
         * parameters — so what was sent is what is kept. This used to return
         * nothing for every kind but ACTION, which meant a prompt typed into an
         * agent node was accepted by the form, saved as nothing, and gone when
         * the page was reopened.
         */
        if (node.kind == NodeKind.AGENT) {
            return sent.values.map { mappingOf(it, refusing) }.toMutableList()
        }

        /*
         * A session node has exactly two parameters, and always both.
         *
         * They are not a catalogue's and not the node's own invention: a session
         * is identified by a key and the prefix it is filed under, and that is
         * the whole of what this kind is. Fixing the list here means the panel
         * cannot be talked into saving a third one, and a node saved before one
         * of them was filled in still comes back with both boxes to fill.
         */
        if (node.kind == NodeKind.SESSION) {
            return SESSION_PARAMETERS
                .map { name -> sent[name]?.let { mappingOf(it, refusing) } ?: NodeMapping(name = name) }
                .toMutableList()
        }

        /*
         * An object node's parameters are its fields.
         *
         * A saved shape decides which there are — a field the shape does not
         * have is dropped, and one it has that the node did not fill arrives
         * empty — so a shape edited afterwards is reflected without anything
         * having to tidy up. A shape of the node's own is whatever it sent.
         */
        if (node.kind == NodeKind.OBJECT) {
            val shape = node.objectId?.let { objects.findByIdOrNull(it) }
                ?: return sent.values.map { mappingOf(it, refusing) }.toMutableList()

            return shape.properties
                .map { property -> sent[property.name]?.let { mappingOf(it, refusing) } ?: NodeMapping(name = property.name) }
                .toMutableList()
        }

        if (node.kind != NodeKind.ACTION) return mutableListOf()
        val action = node.actionId?.let { actions.findByIdOrNull(it) } ?: return mutableListOf()

        // The action decides which parameters exist; the node decides what fills
        // them. A name the action does not have is dropped, and one it has that
        // the node did not send falls back to the action's own suggestion.
        return parameters.defaultsFor(action)
            .map { parameter ->
                sent[parameter.name]
                    ?.let { mappingOf(it, refusing) }
                    ?: NodeMapping(name = parameter.name, expression = parameter.expression, mode = parameter.mode)
            }
            .toMutableList()
    }

    /**
     * A value is text, so text is all it may be.
     *
     * `{{something}}` in a value is somebody expecting a substitution that no
     * longer happens — and the way it fails is silent: the braces are sent, into
     * a Slack message or a channel name, and nothing reports a problem. Refused
     * at the save, where the person who typed it is still looking, with the
     * thing they should have used instead.
     */
    private fun requireNoPlaceholder(sent: NodeMappingInput) {
        if (sent.mode == MappingMode.REFERENCE) return
        if (PLACEHOLDER_IN_VALUE.containsMatchIn(sent.expression)) throw ValueHoldsPlaceholderException(sent.name)
    }

    private fun mappingOf(sent: NodeMappingInput, refusing: Boolean): NodeMapping {
        if (refusing) requireNoPlaceholder(sent)
        return NodeMapping(
        name = sent.name,
        expression = sent.expression,
        mode = sent.mode,
        // Only meaningful on a reference; kept off a value so a switch back does
        // not leave a node key nothing points at.
            sourceNodeKey = sent.sourceNodeKey?.takeIf { sent.mode == MappingMode.REFERENCE },
        )
    }

    /**
     * A trigger node is an instance of a definition from the workspace's catalogue,
     * so the definition has to be one that workspace holds — otherwise a workflow
     * could listen to another workspace's connection.
     */
    private fun requireTriggerBelongsToWorkspace(workspaceId: Long, node: WorkflowNodeInput) {
        val triggerId = node.triggerId ?: return
        if (node.kind != NodeKind.TRIGGER) return
        val trigger = triggers.findByIdOrNull(triggerId) ?: throw TriggerNotInCatalogueException(triggerId)
        if (trigger.workspaceId != workspaceId) throw TriggerNotInCatalogueException(triggerId)
    }

    /**
     * An output name has to be something a later node can actually write.
     *
     * A reference names one field, so a name with a space or a dot in it could
     * be typed here and never referred to anywhere — a setting that looks
     * accepted and quietly does nothing. Refusing it at the save is the only
     * moment the person who typed it is still looking.
     */
    private fun requireReferenceable(outputName: String) {
        if (!REFERENCEABLE.matches(outputName)) throw OutputNameInvalidException(outputName)
    }

    /** An action node runs one of the workspace's actions, and only its own workspace's. */
    private fun requireActionBelongsToWorkspace(workspaceId: Long, node: WorkflowNodeInput) {
        val actionId = node.actionId ?: return
        if (node.kind != NodeKind.ACTION) return
        val action = actions.findByIdOrNull(actionId) ?: throw ActionNotInCatalogueException(actionId)
        if (action.workspaceId != workspaceId) throw ActionNotInCatalogueException(actionId)
    }

    /** An object node makes one of the workspace's shapes, and only its own workspace's. */
    private fun requireObjectBelongsToWorkspace(workspaceId: Long, node: WorkflowNodeInput) {
        val objectId = node.objectId ?: return
        if (node.kind != NodeKind.OBJECT) return
        val shape = objects.findByIdOrNull(objectId) ?: throw ObjectNotInCatalogueException(objectId)
        if (shape.workspaceId != workspaceId) throw ObjectNotInCatalogueException(objectId)
    }

    /** An agent node runs one of the workspace's agents, and only its own workspace's. */
    private fun requireAgentBelongsToWorkspace(workspaceId: Long, node: WorkflowNodeInput) {
        val agentId = node.agentId ?: return
        if (node.kind != NodeKind.AGENT) return
        val agent = agents.findByIdOrNull(agentId) ?: throw AgentNotInCatalogueException(agentId)
        if (agent.workspaceId != workspaceId) throw AgentNotInCatalogueException(agentId)
    }

    /** A condition node asks one of the workspace's conditions, and only its own workspace's. */
    private fun requireConditionBelongsToWorkspace(workspaceId: Long, node: WorkflowNodeInput) {
        val conditionId = node.conditionId ?: return
        if (node.kind != NodeKind.CONDITION) return
        val condition = conditions.findByIdOrNull(conditionId) ?: throw ConditionNotInCatalogueException(conditionId)
        if (condition.workspaceId != workspaceId) throw ConditionNotInCatalogueException(conditionId)
    }

    /**
     * Whether the workspace has this workflow switched on.
     *
     * The editor is told because Run still works on one that is switched off -
     * trying a graph by hand is how it gets fixed - and somebody who cannot see
     * the switch from here would otherwise publish, walk away, and wait for a
     * trigger that is never going to start it.
     */
    private fun enabledIn(workspaceId: Long, workflowId: Long): Boolean =
        assignments.findByWorkspaceIdAndWorkflowId(workspaceId, workflowId)?.enabled != false

    /** The workflow has to be assigned to a workspace the caller can see. */
    private fun requireAssignment(workspaceId: Long, workflowId: Long) {
        val workspace = access.requireVisible(workspaceId)
        if (!assignments.existsByWorkspaceIdAndWorkflowId(workspaceId, workflowId)) {
            throw WorkflowNotFoundException(workflowId)
        }
    }
}

data class WorkflowNodeInput(
    val key: String,
    val kind: NodeKind,
    val name: String,
    val description: String? = null,
    /** The agent an agent node instances; ignored on any other kind. */
    val agentId: Long? = null,
    /** The trigger definition a trigger node instances; ignored on any other kind. */
    val triggerId: Long? = null,
    /** The action an action node instances; ignored on any other kind. */
    val actionId: Long? = null,
    /** The condition a condition node asks; ignored on any other kind. */
    val conditionId: Long? = null,
    /** The saved shape an object node makes; null is a shape of its own. */
    val objectId: Long? = null,
    val outputName: String? = null,
    val icon: String? = null,
    val orientation: NodeOrientation? = null,
    /**
     * What this node passes to its action. Null leaves it to the action's own
     * suggestions, which is what a node freshly pointed at one wants.
     */
    val mappings: List<NodeMappingInput>? = null,
    /**
     * What a node's two ways out are called; null leaves it to the interface,
     * which says Yes and No for a condition and If works / If fails for an
     * action that handles its failure.
     */
    val yesLabel: String? = null,
    val noLabel: String? = null,
    /**
     * Whether this node has a second way out for when it fails; kept on an
     * action and on an agent, ignored on every other kind.
     */
    val fallbackEnabled: Boolean = false,
    /** How many times in all this node may be attempted; null or 1 is once. */
    val retryAttempts: Int? = null,
    /** How long to leave a failed attempt alone before the next; null is none. */
    val retryBackoffSeconds: Int? = null,
    /** How that wait grows from one attempt to the next; null is fixed. */
    val retryBackoff: RetryBackoff? = null,
    val x: Double,
    val y: Double,
)

/** One parameter: the value it holds, or the field it reads. */
data class NodeMappingInput(
    val name: String,
    val expression: String,
    val mode: MappingMode = MappingMode.VALUE,
    val sourceNodeKey: String? = null,
)

data class WorkflowEdgeInput(
    val source: String,
    val target: String,
    /** Which way out of a condition it leaves by; absent for every other edge. */
    val branch: EdgeBranch? = null,
)

data class WorkflowGraphInput(
    val nodes: List<WorkflowNodeInput>,
    val edges: List<WorkflowEdgeInput>,
)

data class WorkflowNodeView(
    val key: String,
    val kind: NodeKind,
    val name: String,
    val description: String?,
    /** The agent this node instances, when it is an agent node. */
    val agentId: Long?,
    val triggerId: Long?,
    val actionId: Long?,
    val conditionId: Long?,
    /** The saved shape an object node makes; null is a shape of its own. */
    val objectId: Long?,
    val outputName: String?,
    val icon: String?,
    /** Which way round it faces on the canvas; null is left to right. */
    val orientation: NodeOrientation?,
    /** What a node's two ways out are called; null leaves it to the interface. */
    val yesLabel: String?,
    val noLabel: String?,
    /** Whether this node has a second way out for when it fails. */
    val fallbackEnabled: Boolean,
    /** How many times in all this node may be attempted; null is once. */
    val retryAttempts: Int?,
    val retryBackoffSeconds: Int?,
    /** How the wait grows from one attempt to the next; null is fixed. */
    val retryBackoff: RetryBackoff?,
    val x: Double,
    val y: Double,
    /** What the node needs, read off whatever it points at. */
    val inputs: List<ActionParamView> = emptyList(),
    /** What it hands on. */
    val outputs: List<ActionParamView> = emptyList(),
    /** What this node passes to its action, one entry per parameter it has. */
    val mappings: List<NodeMappingView> = emptyList(),
) {
    constructor(
        node: WorkflowNode,
        inputs: List<ActionParamView> = emptyList(),
        outputs: List<ActionParamView> = emptyList(),
    ) : this(
        key = node.nodeKey,
        kind = node.kind,
        name = node.name,
        description = node.description,
        agentId = node.agentId,
        triggerId = node.triggerId,
        actionId = node.actionId,
        conditionId = node.conditionId,
        objectId = node.objectId,
        outputName = node.outputName,
        icon = node.icon,
        orientation = node.orientation,
        yesLabel = node.yesLabel,
        noLabel = node.noLabel,
        fallbackEnabled = node.fallbackEnabled,
        retryAttempts = node.retryAttempts,
        retryBackoffSeconds = node.retryBackoffSeconds,
        retryBackoff = node.retryBackoff,
        x = node.positionX,
        y = node.positionY,
        inputs = inputs,
        outputs = outputs,
        mappings = node.mappings.map { NodeMappingView(it.name, it.expression, it.mode, it.sourceNodeKey) },
    )
}

data class NodeMappingView(
    val name: String,
    val expression: String,
    val mode: MappingMode = MappingMode.VALUE,
    val sourceNodeKey: String? = null,
)

data class WorkflowEdgeView(
    val source: String,
    val target: String,
    val branch: EdgeBranch? = null,
) {
    constructor(edge: WorkflowEdge) : this(
        source = edge.sourceKey,
        target = edge.targetKey,
        branch = edge.branch,
    )
}

/**
 * One publication of a workflow: a version of it, and possibly the live one.
 *
 * Without the graph. A list of these is a history somebody scrolls, and every
 * row carrying a whole serialised graph would be a page measured in megabytes;
 * `workflowPublicationGraph` fetches one when a row is opened.
 */
data class WorkflowPublicationView(
    val id: Long,
    val workflowId: Long,
    val publishedAt: String,
    val publishedBy: String,
    /** The one the runner reads. Exactly one publication of each workflow is. */
    val current: Boolean,
    /** The publication this one copied, when it was made by restoring one. */
    val restoredFrom: Long?,
) {
    constructor(publication: WorkflowPublication, current: Boolean) : this(
        id = requireNotNull(publication.id),
        workflowId = publication.workflowId,
        publishedAt = publication.publishedAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        publishedBy = publication.publishedBy,
        current = current,
        restoredFrom = publication.restoredFrom,
    )
}

class WorkflowPublicationNotFoundException(id: Long) :
    RuntimeException("No publication with id $id")

data class WorkflowGraphView(
    val workflowId: Long,
    val name: String,
    val description: String?,
    val status: WorkflowStatus,
    /** Whether the workspace has it switched on; see `enabledIn`. */
    val enabled: Boolean = true,
    val nodes: List<WorkflowNodeView>,
    val edges: List<WorkflowEdgeView>,
    /** Everything the graph is missing, worst first; empty when it holds together. */
    val problems: List<GraphProblem> = emptyList(),
)

class WorkflowGraphEmptyException : RuntimeException("Add at least one node before publishing")

/**
 * What a name has to look like to be referred to: a letter or underscore, then
 * letters, digits and underscores. A field picked from a list has to be one
 * name, and this is what makes it one.
 */
private val REFERENCEABLE = Regex("[A-Za-z_][A-Za-z0-9_]*")

/** Braces in a value: the substitution somebody still expects and will not get. */
private val PLACEHOLDER_IN_VALUE = Regex("""\{\{[^}]*\}\}""")

/**
 * What a retry policy may be set to.
 *
 * Bounded here rather than refused, because neither end is a mistake worth
 * arguing with somebody over: nought attempts is a typo for one, and a hundred
 * is somebody who has not thought about what a run costs. The ceiling on the
 * backoff is an hour, which is longer than any single wait a workflow has
 * needed and short enough that a run cannot disappear for a day over a typo.
 * A doubling curve is bounded by the same hour where it is spent rather than
 * here, because what this holds is the first wait and not what it grows into.
 */
/**
 * How much of a workflow's history a screen asks for, and the most it may.
 *
 * A page shows a handful and a workflow published every morning for a year has
 * three hundred and sixty five of them; the tail is what anybody reads, so the
 * list is limited rather than paged.
 */
private const val DEFAULT_PUBLICATIONS = 25
private const val MOST_PUBLICATIONS = 200

private const val MIN_ATTEMPTS = 1
private const val MAX_ATTEMPTS = 10
private const val MAX_BACKOFF_SECONDS = 3600

class ValueHoldsPlaceholderException(parameter: String) : RuntimeException(
    "\"$parameter\" is a value holding {{...}}, which is sent as those characters. " +
        "Switch it to a reference and pick the field instead.",
)

class OutputNameInvalidException(name: String) : RuntimeException(
    "\"$name\" cannot be referred to. An output name is letters, digits and underscores, " +
        "starting with a letter — a later node has to be able to point at it",
)

class TriggerNotInCatalogueException(id: Long) :
    RuntimeException("Trigger $id is not in this workspace's catalogue")

class ActionNotInCatalogueException(id: Long) :
    RuntimeException("Action $id is not in this workspace's catalogue")

class ObjectNotInCatalogueException(id: Long) :
    RuntimeException("Object $id is not in this workspace's catalogue")

class AgentNotInCatalogueException(id: Long) :
    RuntimeException("No agent with id $id in this workspace")

class ConditionNotInCatalogueException(id: Long) :
    RuntimeException("Condition $id is not in this workspace's catalogue")
