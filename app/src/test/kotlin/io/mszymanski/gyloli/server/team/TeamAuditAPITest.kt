package io.mszymanski.gyloli.server.team

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser
import java.time.OffsetDateTime

@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class TeamAuditAPITest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val repository: TeamAuditRepository,
    @Autowired val teams: TeamRepository,
) {

    private val start = OffsetDateTime.parse("2026-08-14T09:00:00+02:00")

    // Filtering by team resolves the team first, so the ids have to be real.
    private var teamId: Long = 0
    private var otherTeamId: Long = 0

    @BeforeEach
    fun seedAudit() {
        repository.deleteAll()
        teams.deleteAll()
        teamId = requireNotNull(teams.save(Team(name = "backend")).id)
        otherTeamId = requireNotNull(teams.save(Team(name = "research")).id)
        repository.saveAll(
            listOf(
                TeamAudit(
                    teamId = teamId,
                    newTeamName = "backend",
                    message = "seeded",
                    operationType = TeamOperationType.ADD,
                    date = start,
                    userId = "alice",
                ),
                TeamAudit(
                    teamId = teamId,
                    oldTeamName = "backend",
                    newTeamName = "core",
                    message = "seeded",
                    operationType = TeamOperationType.RENAME,
                    date = start.plusHours(1),
                    userId = "bob",
                ),
                TeamAudit(
                    teamId = otherTeamId,
                    oldTeamName = "research",
                    message = "seeded",
                    operationType = TeamOperationType.REMOVE,
                    date = start.plusHours(2),
                    userId = "alice",
                ),
            ),
        )
    }

    @Test
    fun `the organization log leaves a team's own business to that team`() {
        // What a team did inside itself: the team's log has it, and the
        // organization's does not.
        repository.save(
            TeamAudit(
                teamId = teamId,
                category = TeamAuditCategory.WORKFLOW,
                message = "Condition Is Teammate Message created",
                date = start.plusHours(2),
                userId = "alice",
            ),
        )

        val organization = graphQlTester.document(
            """query { teamAudit(size: 50) { content { message } } }""",
        ).execute().path("teamAudit.content[*].message").entityList(String::class.java).get()
        assertThat(organization).noneMatch { it.contains("Condition") }

        val team = graphQlTester.document(
            """query { teamActivity(teamId: $teamId, size: 50) { content { message } } }""",
        ).execute().path("teamActivity.content[*].message").entityList(String::class.java).get()
        assertThat(team).anyMatch { it.contains("Condition") }
    }

    @Test
    fun `returns every entry newest first`() {
        graphQlTester.document(
            """
            query {
              teamAudit {
                content { teamId oldTeamName newTeamName operationType date userId }
                totalElements
              }
            }
            """,
        ).execute()
            .path("teamAudit.content[*].operationType").entityList(String::class.java)
            .containsExactly("REMOVE", "RENAME", "ADD")
            .path("teamAudit.content[0].userId").entity(String::class.java).isEqualTo("alice")
            .path("teamAudit.content[0].oldTeamName").entity(String::class.java).isEqualTo("research")
            .path("teamAudit.content[0].newTeamName").valueIsNull()
            // timestamptz keeps the instant, not the original offset, so it reads back as UTC.
            .path("teamAudit.content[0].date").entity(String::class.java)
            .isEqualTo("2026-08-14T09:00:00Z")
            .path("teamAudit.totalElements").entity(Int::class.java).isEqualTo(3)
    }

    @Test
    fun `filters by team`() {
        graphQlTester.document("""query { teamAudit(teamId: $teamId) { content { newTeamName } totalElements } }""")
            .execute()
            .path("teamAudit.content[*].newTeamName").entityList(String::class.java)
            .containsExactly("core", "backend")
            .path("teamAudit.totalElements").entity(Int::class.java).isEqualTo(2)
    }

    @Test
    fun `pages through entries`() {
        graphQlTester.document("""query { teamAudit(page: 0, size: 2) { content { operationType } totalPages } }""")
            .execute()
            .path("teamAudit.content[*].operationType").entityList(String::class.java)
            .containsExactly("REMOVE", "RENAME")
            .path("teamAudit.totalPages").entity(Int::class.java).isEqualTo(2)

        graphQlTester.document("""query { teamAudit(page: 1, size: 2) { content { operationType } } }""")
            .execute()
            .path("teamAudit.content[*].operationType").entityList(String::class.java)
            .containsExactly("ADD")
    }
}
