package io.mszymanski.gyloli.server.security

import io.mszymanski.gyloli.server.team.Team
import io.mszymanski.gyloli.server.team.TeamAudit
import io.mszymanski.gyloli.server.team.TeamAuditRepository
import io.mszymanski.gyloli.server.team.TeamOperationType
import io.mszymanski.gyloli.server.team.TeamRepository
import io.mszymanski.gyloli.server.workflow.TeamWorkflow
import io.mszymanski.gyloli.server.workflow.TeamWorkflowRepository
import io.mszymanski.gyloli.server.workflow.Workflow
import io.mszymanski.gyloli.server.workflow.WorkflowRepository
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
 * Visibility comes from directory group membership: the configured admin role
 * sees everything, everyone else needs to be in the group named on the team.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
class TeamVisibilityTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val teams: TeamRepository,
    @Autowired val audit: TeamAuditRepository,
    @Autowired val workflows: WorkflowRepository,
    @Autowired val assignments: TeamWorkflowRepository,
) {

    private var backendId: Long = 0
    private var frontendId: Long = 0

    @BeforeEach
    fun seed() {
        assignments.deleteAll()
        workflows.deleteAll()
        audit.deleteAll()
        teams.deleteAll()
        backendId = requireNotNull(
            teams.save(Team(name = "backend", ldapGroup = "cn=backend,ou=teams,dc=gyloli,dc=io")).id,
        )
        frontendId = requireNotNull(
            teams.save(Team(name = "frontend", ldapGroup = "cn=frontend,ou=teams,dc=gyloli,dc=io")).id,
        )
    }

    @Test
    @WithMockUser(username = "alice", roles = ["ADMINS"])
    fun `an admin sees every team`() {
        graphQlTester.document("""query { teams { content { name } totalElements } }""")
            .execute()
            .path("teams.content[*].name").entityList(String::class.java).containsExactly("backend", "frontend")
            .path("teams.totalElements").entity(Int::class.java).isEqualTo(2)
    }

    @Test
    @WithMockUser(username = "bob", roles = ["BACKEND"])
    fun `a member sees only the teams whose group they belong to`() {
        graphQlTester.document("""query { teams { content { name } totalElements totalPages } }""")
            .execute()
            .path("teams.content[*].name").entityList(String::class.java).containsExactly("backend")
            .path("teams.totalElements").entity(Int::class.java).isEqualTo(1)
            .path("teams.totalPages").entity(Int::class.java).isEqualTo(1)
    }

    @Test
    @WithMockUser(username = "nobody", roles = ["USERS"])
    fun `someone in no team group sees nothing`() {
        graphQlTester.document("""query { teams { content { name } totalElements } }""")
            .execute()
            .path("teams.content").entityList(String::class.java).hasSize(0)
            .path("teams.totalElements").entity(Int::class.java).isEqualTo(0)
    }

    @Test
    @WithMockUser(username = "bob", roles = ["BACKEND"])
    fun `a team with no directory group is administrators-only`() {
        val orphan = teams.save(Team(name = "secret"))

        graphQlTester.document("""query { team(id: ${orphan.id}) { name } }""")
            .execute().path("team").valueIsNull()

        graphQlTester.document("""query { teams { content { name } } }""")
            .execute()
            .path("teams.content[*].name").entityList(String::class.java).containsExactly("backend")
    }

    @Test
    @WithMockUser(username = "bob", roles = ["BACKEND"])
    fun `a hidden team reads as missing`() {
        graphQlTester.document("""query { team(id: $backendId) { name } }""")
            .execute().path("team.name").entity(String::class.java).isEqualTo("backend")

        graphQlTester.document("""query { team(id: $frontendId) { name } }""")
            .execute().path("team").valueIsNull()
    }

    @Test
    @WithMockUser(username = "bob", roles = ["BACKEND"])
    fun `a member cannot create, rename or delete teams`() {
        forbidden(
            """mutation { createTeam(input: { name: "platform" }) { id } }""",
            "This action requires the organization administrator role",
        )
        forbidden(
            """mutation { updateTeam(id: $backendId, input: { name: "core" }) { id } }""",
            "This action requires the organization administrator role",
        )
        forbidden(
            """mutation { deleteTeam(id: $backendId) }""",
            "This action requires the organization administrator role",
        )

        assertThat(teams.findAll().map { it.name }).containsExactlyInAnyOrder("backend", "frontend")
    }

    @Test
    @WithMockUser(username = "bob", roles = ["BACKEND"])
    fun `a member cannot reach another team's workflows`() {
        graphQlTester.document("""query { teamWorkflows(teamId: $backendId) { totalElements } }""")
            .execute().path("teamWorkflows.totalElements").entity(Int::class.java).isEqualTo(0)

        forbidden(
            """query { teamWorkflows(teamId: $frontendId) { totalElements } }""",
            """You do not have access to team "frontend"""",
        )
        forbidden(
            """mutation { createWorkflow(input: { teamId: $frontendId, name: "Sneaky" }) { id } }""",
            """You do not have access to team "frontend"""",
        )

        assertThat(workflows.findAll()).isEmpty()
    }

    @Test
    @WithMockUser(username = "bob", roles = ["BACKEND"])
    fun `a member cannot toggle or remove another team's workflow`() {
        val workflow = workflows.save(Workflow(name = "Frontend Deploy"))
        val assignment = assignments.save(TeamWorkflow(teamId = frontendId, workflow = workflow))

        forbidden(
            """mutation { setWorkflowEnabled(id: ${assignment.id}, enabled: false) { enabled } }""",
            """You do not have access to team "frontend"""",
        )
        forbidden(
            """mutation { removeWorkflow(id: ${assignment.id}) }""",
            """You do not have access to team "frontend"""",
        )

        assertThat(assignments.findAll()).hasSize(1)
    }

    @Test
    @WithMockUser(username = "bob", roles = ["BACKEND"])
    fun `the audit log only shows entries for visible teams`() {
        audit.save(entry(backendId, "backend"))
        audit.save(entry(frontendId, "frontend"))

        graphQlTester.document("""query { teamAudit { content { newTeamName } totalElements } }""")
            .execute()
            .path("teamAudit.content[*].newTeamName").entityList(String::class.java).containsExactly("backend")
            .path("teamAudit.totalElements").entity(Int::class.java).isEqualTo(1)

        forbidden(
            """query { teamAudit(teamId: $frontendId) { totalElements } }""",
            """You do not have access to team "frontend"""",
        )
    }

    private fun entry(teamId: Long, name: String) = TeamAudit(
        teamId = teamId,
        newTeamName = name,
        message = "seeded",
        operationType = TeamOperationType.ADD,
        date = OffsetDateTime.now(),
        userId = "alice",
    )

    private fun forbidden(document: String, message: String) {
        graphQlTester.document(document)
            .execute()
            .errors()
            .satisfy { errors ->
                assertThat(errors).singleElement().extracting { it.message }.isEqualTo(message)
            }
    }
}
