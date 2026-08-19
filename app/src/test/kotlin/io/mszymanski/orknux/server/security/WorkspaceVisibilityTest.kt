package io.mszymanski.orknux.server.security

import io.mszymanski.orknux.server.action.ActionSubtype
import io.mszymanski.orknux.server.action.ActionType
import io.mszymanski.orknux.server.action.WorkflowAction
import io.mszymanski.orknux.server.action.WorkflowActionRepository
import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.agent.AgentType
import io.mszymanski.orknux.server.issue.Issue
import io.mszymanski.orknux.server.issue.IssueRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAudit
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceOperationType
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import io.mszymanski.orknux.server.workflow.WorkspaceWorkflow
import io.mszymanski.orknux.server.workflow.WorkspaceWorkflowRepository
import io.mszymanski.orknux.server.workflow.Workflow
import io.mszymanski.orknux.server.workflow.WorkflowRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser
import java.time.OffsetDateTime

/**
 * Visibility comes from roles: the configured admin authority sees everything,
 * everyone else needs to hold a role the workspace is assigned.
 *
 * The roles here are granted by name — a caller holding `ROLE_BACKEND` holds the
 * role called `backend` — which is the path that keeps installations working when
 * they upgrade into roles without writing any mapping.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
class WorkspaceVisibilityTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
    @Autowired val workflows: WorkflowRepository,
    @Autowired val assignments: WorkspaceWorkflowRepository,
    @Autowired val roles: RoleRepository,
    @Autowired val actions: WorkflowActionRepository,
    @Autowired val agents: AgentRepository,
    @Autowired val issues: IssueRepository,
) {

    private var backendId: Long = 0
    private var frontendId: Long = 0

    @BeforeEach
    fun seed() {
        issues.deleteAll()
        agents.deleteAll()
        actions.deleteAll()
        assignments.deleteAll()
        workflows.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        // Everything but the built-in role, which is this installation's and not
        // any test's to remove.
        roles.deleteAll(roles.findAll().filterNot { it.builtin })

        val backend = roles.save(Role(name = "backend"))
        val frontend = roles.save(Role(name = "frontend"))
        backendId = requireNotNull(
            workspaces.save(Workspace(name = "backend", roles = mutableSetOf(backend))).id,
        )
        frontendId = requireNotNull(
            workspaces.save(Workspace(name = "frontend", roles = mutableSetOf(frontend))).id,
        )
    }

    @Test
    @WithMockUser(username = "alice", roles = ["ADMINS"])
    fun `an admin sees every workspace`() {
        graphQlTester.document("""query { workspaces { content { name } totalElements } }""")
            .execute()
            .path("workspaces.content[*].name").entityList(String::class.java).containsExactly("backend", "frontend")
            .path("workspaces.totalElements").entity(Int::class.java).isEqualTo(2)
    }

    @Test
    @WithMockUser(username = "bob", roles = ["BACKEND"])
    fun `a member sees only the workspaces whose role they hold`() {
        graphQlTester.document("""query { workspaces { content { name } totalElements totalPages } }""")
            .execute()
            .path("workspaces.content[*].name").entityList(String::class.java).containsExactly("backend")
            .path("workspaces.totalElements").entity(Int::class.java).isEqualTo(1)
            .path("workspaces.totalPages").entity(Int::class.java).isEqualTo(1)
    }

    @Test
    @WithMockUser(username = "nobody", roles = ["USERS"])
    fun `someone holding no workspace role sees nothing`() {
        graphQlTester.document("""query { workspaces { content { name } totalElements } }""")
            .execute()
            .path("workspaces.content").entityList(String::class.java).hasSize(0)
            .path("workspaces.totalElements").entity(Int::class.java).isEqualTo(0)
    }

    @Test
    @WithMockUser(username = "bob", roles = ["BACKEND"])
    fun `a workspace with no roles is administrators-only`() {
        val orphan = workspaces.save(Workspace(name = "secret"))

        graphQlTester.document("""query { workspace(id: ${orphan.id}) { name } }""")
            .execute().path("workspace").valueIsNull()

        graphQlTester.document("""query { workspaces { content { name } } }""")
            .execute()
            .path("workspaces.content[*].name").entityList(String::class.java).containsExactly("backend")
    }

    @Test
    @WithMockUser(username = "bob", roles = ["BACKEND"])
    fun `a hidden workspace reads as missing`() {
        graphQlTester.document("""query { workspace(id: $backendId) { name } }""")
            .execute().path("workspace.name").entity(String::class.java).isEqualTo("backend")

        graphQlTester.document("""query { workspace(id: $frontendId) { name } }""")
            .execute().path("workspace").valueIsNull()
    }

    @Test
    @WithMockUser(username = "bob", roles = ["BACKEND"])
    fun `a member cannot create, rename or delete workspaces`() {
        forbidden(
            """mutation { createWorkspace(input: { name: "platform" }) { id } }""",
            "This action requires the administrator role",
        )
        forbidden(
            """mutation { updateWorkspace(id: $backendId, input: { name: "core" }) { id } }""",
            "This action requires the administrator role",
        )
        forbidden(
            """mutation { deleteWorkspace(id: $backendId) }""",
            "This action requires the administrator role",
        )

        assertThat(workspaces.findAll().map { it.name }).containsExactlyInAnyOrder("backend", "frontend")
    }

    @Test
    @WithMockUser(username = "bob", roles = ["BACKEND"])
    fun `a member cannot reach another workspace's workflows`() {
        graphQlTester.document("""query { workspaceWorkflows(workspaceId: $backendId) { totalElements } }""")
            .execute().path("workspaceWorkflows.totalElements").entity(Int::class.java).isEqualTo(0)

        // Both in the words an id nothing was saved under gets, which is the
        // point: see the test below for why they have to be the same sentence.
        forbidden(
            """query { workspaceWorkflows(workspaceId: $frontendId) { totalElements } }""",
            "No workspace with id $frontendId",
        )
        forbidden(
            """mutation { createWorkflow(input: { workspaceId: $frontendId, name: "Sneaky" }) { id } }""",
            "No workspace with id $frontendId",
        )

        assertThat(workflows.findAll()).isEmpty()
    }

    /**
     * Still refused, and now refused in the words the absent case uses.
     *
     * It used to say "you do not have access to it", which is a different
     * sentence from the one an invented id gets - and the difference is the
     * whole answer. Toggling asserts against the made-up id rather than against
     * a literal message, because what matters is that they match.
     */
    @Test
    @WithMockUser(username = "bob", roles = ["BACKEND"])
    fun `a member cannot toggle or remove another workspace's workflow`() {
        val workflow = workflows.save(Workflow(name = "Frontend Deploy"))
        val assignment = assignments.save(WorkspaceWorkflow(workspaceId = frontendId, workflow = workflow))

        answersAlike(requireNotNull(assignment.id)) {
            """mutation { setWorkflowEnabled(id: $it, enabled: false) { enabled } }"""
        }

        graphQlTester.document("""mutation { removeWorkflow(id: ${assignment.id}) }""")
            .execute().path("removeWorkflow").entity(Boolean::class.java).isEqualTo(false)

        assertThat(assignments.findAll()).hasSize(1)
    }

    @Test
    @WithMockUser(username = "bob", roles = ["BACKEND"])
    fun `the audit log only shows entries for visible workspaces`() {
        audit.save(entry(backendId, "backend"))
        audit.save(entry(frontendId, "frontend"))

        graphQlTester.document("""query { workspaceAudit { content { newWorkspaceName } totalElements } }""")
            .execute()
            .path("workspaceAudit.content[*].newWorkspaceName").entityList(String::class.java).containsExactly("backend")
            .path("workspaceAudit.totalElements").entity(Int::class.java).isEqualTo(1)

        forbidden(
            """query { workspaceAudit(workspaceId: $frontendId) { totalElements } }""",
            "That does not exist, or you do not have access to it",
        )
    }

    /**
     * A refusal used to read "You do not have access to workspace "frontend"",
     * which answers a question nobody may ask: the name of a workspace the
     * caller is not in, handed over by any id that happens to belong to it.
     * GraphQL reports errors with a 200, so trying every id in turn is a script.
     */
    @Test
    @WithMockUser(username = "bob", roles = ["BACKEND"])
    fun `a refusal never names the workspace it is protecting`() {
        graphQlTester.document("""query { workspaceWorkflows(workspaceId: $frontendId) { totalElements } }""")
            .execute()
            .errors()
            .satisfy { errors ->
                assertThat(errors.single().message).doesNotContain("frontend")
                // Answered as absent rather than as forbidden, which is what the
                // REST side has always said to the same exception.
                assertThat(errors.single().errorType.toString()).isEqualTo("NOT_FOUND")
            }
    }

    /**
     * The other half of the same leak.
     *
     * A refusal that no longer names the workspace still answers a real id
     * differently from an arbitrary one, so walking the numbers still maps out
     * what exists here and roughly how much of it there is. Answering null makes
     * an entity in a workspace the caller cannot see indistinguishable from one
     * that was never created.
     */
    @Test
    @WithMockUser(username = "bob", roles = ["BACKEND"])
    fun `an entity in a hidden workspace reads as one that is not there`() {
        val mine = requireNotNull(actions.save(action(backendId, "Mine")).id)
        val theirs = requireNotNull(actions.save(action(frontendId, "Theirs")).id)

        graphQlTester.document("""query { action(id: $mine) { name } }""")
            .execute().path("action.name").entity(String::class.java).isEqualTo("Mine")

        graphQlTester.document("""query { action(id: $theirs) { name } }""")
            .execute().path("action").valueIsNull()
    }

    /**
     * The two answers have to be the same answer, not merely both empty: an
     * error for one and null for the other is still a yes and a no.
     */
    @Test
    @WithMockUser(username = "bob", roles = ["BACKEND"])
    fun `an id that is not yours answers exactly as an id that is not real`() {
        val theirs = requireNotNull(agents.save(agent(frontendId)).id)

        val notYours = graphQlTester.document("""query { agent(id: $theirs) { name } }""").execute()
        val notReal = graphQlTester.document("""query { agent(id: 999999) { name } }""").execute()

        notYours.path("agent").valueIsNull()
        notReal.path("agent").valueIsNull()
    }

    @Test
    @WithMockUser(username = "bob", roles = ["BACKEND"])
    fun `an agent in a visible workspace is still returned`() {
        val mine = requireNotNull(agents.save(agent(backendId)).id)

        graphQlTester.document("""query { agent(id: $mine) { name } }""")
            .execute().path("agent.name").entity(String::class.java).isEqualTo("Helper")
    }

    /**
     * The issue query takes a workspace id rather than the issue's own, so the
     * whole query answers null rather than refusing - otherwise the workspace id
     * itself is the number to walk.
     */
    @Test
    @WithMockUser(username = "bob", roles = ["BACKEND"])
    fun `an issue in a hidden workspace reads as one that is not there`() {
        issues.save(issue(backendId, "Mine"))
        issues.save(issue(frontendId, "Theirs"))

        graphQlTester.document("""query { workspaceIssue(workspaceId: $backendId, number: 1) { title } }""")
            .execute().path("workspaceIssue.title").entity(String::class.java).isEqualTo("Mine")

        graphQlTester.document("""query { workspaceIssue(workspaceId: $frontendId, number: 1) { title } }""")
            .execute().path("workspaceIssue").valueIsNull()
    }

    /**
     * The mutation half of the same leak.
     *
     * A mutation has no null to answer with, so the invisible case throws
     * whatever the absent case already threw - the same exception with the same
     * words. Two errors that differed only in wording said "this id is real" as
     * plainly as naming the workspace did, and the walk that maps an
     * installation is the same walk either way.
     */
    @Test
    @WithMockUser(username = "bob", roles = ["BACKEND"])
    fun `a mutation on an entity in a hidden workspace answers exactly as one on a made-up id`() {
        val theirs = requireNotNull(agents.save(agent(frontendId)).id)

        answersAlike(theirs) { """mutation { setAgentEnabled(id: $it, enabled: false) { enabled } }""" }
        assertThat(agents.findAll().single().enabled).isTrue()
    }

    /** A second aggregate, so this is a rule rather than one file's habit. */
    @Test
    @WithMockUser(username = "bob", roles = ["BACKEND"])
    fun `commenting on a hidden issue answers exactly as commenting on one that is not there`() {
        val theirs = requireNotNull(issues.save(issue(frontendId, "Theirs")).id)

        answersAlike(theirs) { """mutation { commentOnIssue(id: $it, content: "Seen") { id } }""" }
        // Nothing was written on it: a comment stamps the issue as it lands.
        assertThat(issues.findAll().single().lastCommentAt).isNull()
    }

    /**
     * A delete answers false for an id that is not there, so it answers false
     * for one that is not the caller's. An error would have been the tell.
     */
    @Test
    @WithMockUser(username = "bob", roles = ["BACKEND"])
    fun `deleting an entity in a hidden workspace answers as deleting nothing does`() {
        val theirs = requireNotNull(actions.save(action(frontendId, "Theirs")).id)

        graphQlTester.document("""mutation { deleteAction(id: $theirs) }""")
            .execute().path("deleteAction").entity(Boolean::class.java).isEqualTo(false)
        graphQlTester.document("""mutation { deleteAction(id: $INVENTED) }""")
            .execute().path("deleteAction").entity(Boolean::class.java).isEqualTo(false)

        assertThat(actions.findAll()).hasSize(1)
    }

    /**
     * The half that matters most: refusing everything would satisfy all three
     * of the above and leave nobody able to change anything.
     */
    @Test
    @WithMockUser(username = "bob", roles = ["BACKEND"])
    fun `a mutation on an entity in a visible workspace still goes through`() {
        val mine = requireNotNull(agents.save(agent(backendId)).id)

        graphQlTester.document("""mutation { setAgentEnabled(id: $mine, enabled: false) { enabled } }""")
            .execute().path("setAgentEnabled.enabled").entity(Boolean::class.java).isEqualTo(false)
        assertThat(agents.findAll().single().enabled).isFalse()
    }

    private fun action(workspaceId: Long, name: String) = WorkflowAction(
        workspaceId = workspaceId,
        name = name,
        type = ActionType.EXECUTE,
        subtype = ActionSubtype.HTTP_REQUEST,
        url = "https://example.test/",
    )

    private fun agent(workspaceId: Long) = Agent(
        workspaceId = workspaceId,
        name = "Helper",
        type = AgentType.LLM,
    )

    private fun issue(workspaceId: Long, title: String) = Issue(
        workspaceId = workspaceId,
        number = 1,
        title = title,
        reporter = "alice",
    )

    private fun entry(workspaceId: Long, name: String) = WorkspaceAudit(
        workspaceId = workspaceId,
        newWorkspaceName = name,
        message = "seeded",
        operationType = WorkspaceOperationType.ADD,
        date = OffsetDateTime.now(),
        userId = "alice",
    )

    /**
     * The single error a document produced, as the pair a caller can tell apart:
     * what it said and what type it was. Comparing two of these is the whole
     * question - not what the words are, but that they are the same words.
     */
    private fun refusal(document: String): Pair<String, String> {
        var answer = "" to ""
        graphQlTester.document(document)
            .execute()
            .errors()
            .satisfy { errors ->
                answer = errors.single().message.orEmpty() to errors.single().errorType.toString()
            }
        return answer
    }

    /**
     * Sends the same mutation twice - once with an id in a workspace the caller
     * cannot see, once with an id nothing was ever saved under - and holds that
     * the two were told the same thing.
     *
     * The number the caller sent is echoed back in the message, so the invented
     * one is put through the same substitution before the two are compared.
     * What is being held still is the sentence, not the id the caller already
     * knew because they typed it.
     */
    private fun answersAlike(hidden: Long, mutation: (Long) -> String) {
        val notYours = refusal(mutation(hidden))
        val (message, type) = refusal(mutation(INVENTED))
        assertThat(notYours).isEqualTo(message.replace("$INVENTED", "$hidden") to type)
    }

    /**
     * The last of the leak #67 closed for entities, closed here for the ids a
     * caller supplies directly.
     *
     * A create path takes a workspace id and nothing else, so it cannot answer
     * null the way a query can - it has to throw. What matters is that it throws
     * the *same* thing either way: an id nothing was saved under and an id that
     * is somebody else's have to be one answer, or walking the numbers still
     * counts the workspaces on this installation.
     *
     * Asserted as one message rather than two assertions on purpose. Two
     * different-but-both-refusing messages would pass a weaker test and leak
     * exactly as much.
     */
    @Test
    @WithMockUser(username = "bob", roles = ["BACKEND"])
    fun `a workspace id that is not yours refuses as one that is not there`() {
        val missing = frontendId + 10_000

        val hidden = messageFrom("""mutation { createVariableCatalog(workspaceId: $frontendId, name: "keys") { id } }""")
        val absent = messageFrom("""mutation { createVariableCatalog(workspaceId: $missing, name: "keys") { id } }""")

        assertThat(hidden).isEqualTo("No workspace with id $frontendId")
        assertThat(absent).isEqualTo("No workspace with id $missing")
    }

    /**
     * And the same for a caller holding no workspace role at all, since that is
     * the caller who would actually be walking the numbers.
     */
    @Test
    @WithMockUser(username = "nobody", roles = ["USERS"])
    fun `someone in no workspace is told nothing exists`() {
        assertThat(messageFrom("""mutation { createVariableCatalog(workspaceId: $backendId, name: "keys") { id } }"""))
            .isEqualTo("No workspace with id $backendId")
    }

    private fun messageFrom(document: String): String {
        var message = ""
        graphQlTester.document(document)
            .execute()
            .errors()
            .satisfy { errors -> message = errors.single().message.orEmpty() }
        return message
    }

    private fun forbidden(document: String, message: String) {
        graphQlTester.document(document)
            .execute()
            .errors()
            .satisfy { errors ->
                assertThat(errors).singleElement().extracting { it.message }.isEqualTo(message)
            }
    }

    private companion object {
        /** An id nothing was ever saved under, which is the answer to match. */
        const val INVENTED = 999999L
    }
}
