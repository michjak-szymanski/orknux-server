package io.mszymanski.gyloli.server.team

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser

@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class TeamAPITest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val repository: TeamRepository,
    @Autowired val auditRepository: TeamAuditRepository,
) {

    @BeforeEach
    fun clearTeams() {
        auditRepository.deleteAll()
        repository.deleteAll()
    }

    @Test
    fun `creates a team and reads it back`() {
        val id = graphQlTester.document(
            """
            mutation { createTeam(input: { name: "platform" }) { id name } }
            """,
        ).execute()
            .path("createTeam.name").entity(String::class.java).isEqualTo("platform")
            .path("createTeam.id").entity(String::class.java).get()

        assertThat(repository.findAll().single().id).isEqualTo(id.toLong())

        graphQlTester.document("""query { team(id: $id) { name } }""")
            .execute().path("team.name").entity(String::class.java).isEqualTo("platform")
    }

    @Test
    fun `creates a team with a description`() {
        graphQlTester.document(
            """
            mutation {
              createTeam(input: { name: "backend", description: "Core API, services, and data pipelines." }) {
                name
                description
              }
            }
            """,
        ).execute()
            .path("createTeam.description").entity(String::class.java)
            .isEqualTo("Core API, services, and data pipelines.")

        assertThat(repository.findAll().single().description)
            .isEqualTo("Core API, services, and data pipelines.")
    }

    @Test
    fun `leaves the description null when it is not supplied`() {
        graphQlTester.document("""mutation { createTeam(input: { name: "platform" }) { description } }""")
            .execute().path("createTeam.description").valueIsNull()
    }

    @Test
    fun `rejects a duplicate team name with a readable message`() {
        repository.save(Team(name = "platform"))

        graphQlTester.document("""mutation { createTeam(input: { name: "platform" }) { id } }""")
            .execute()
            .errors()
            .satisfy { errors ->
                assertThat(errors).singleElement()
                    .extracting { it.message }
                    .isEqualTo("""A team named "platform" already exists""")
            }

        assertThat(repository.findAll()).hasSize(1)
        assertThat(auditRepository.findAll()).isEmpty()
    }

    @Test
    fun `rejects a blank team name`() {
        graphQlTester.document("""mutation { createTeam(input: { name: "   " }) { id } }""")
            .execute()
            .errors()
            .satisfy { errors ->
                assertThat(errors).singleElement()
                    .extracting { it.message }
                    .isEqualTo("A team name is required")
            }

        assertThat(repository.findAll()).isEmpty()
    }

    @Test
    fun `trims the name and drops an empty description`() {
        graphQlTester.document("""mutation { createTeam(input: { name: "  platform  ", description: "  " }) { name description } }""")
            .execute()
            .path("createTeam.name").entity(String::class.java).isEqualTo("platform")
            .path("createTeam.description").valueIsNull()
    }

    @Test
    fun `lists all teams`() {
        repository.saveAll(listOf(Team(name = "platform"), Team(name = "research")))

        graphQlTester.document("""query { teams { content { name } totalElements totalPages } }""")
            .execute()
            .path("teams.content[*].name").entityList(String::class.java)
            .containsExactly("platform", "research")
            .path("teams.totalElements").entity(Int::class.java).isEqualTo(2)
            .path("teams.totalPages").entity(Int::class.java).isEqualTo(1)
    }

    @Test
    fun `pages through teams ordered by name`() {
        repository.saveAll(listOf(Team(name = "research"), Team(name = "platform"), Team(name = "design")))

        graphQlTester.document("""query { teams(page: 0, size: 2) { content { name } page size totalElements totalPages } }""")
            .execute()
            .path("teams.content[*].name").entityList(String::class.java)
            .containsExactly("design", "platform")
            .path("teams.page").entity(Int::class.java).isEqualTo(0)
            .path("teams.size").entity(Int::class.java).isEqualTo(2)
            .path("teams.totalElements").entity(Int::class.java).isEqualTo(3)
            .path("teams.totalPages").entity(Int::class.java).isEqualTo(2)

        graphQlTester.document("""query { teams(page: 1, size: 2) { content { name } } }""")
            .execute()
            .path("teams.content[*].name").entityList(String::class.java)
            .containsExactly("research")

        graphQlTester.document("""query { teams(page: 9, size: 2) { content { name } totalElements } }""")
            .execute()
            .path("teams.content").entityList(String::class.java).hasSize(0)
            .path("teams.totalElements").entity(Int::class.java).isEqualTo(3)
    }

    @Test
    fun `clamps out-of-range paging arguments`() {
        repository.saveAll((1..5).map { Team(name = "team-$it") })

        graphQlTester.document("""query { teams(page: -1, size: 0) { content { name } page size } }""")
            .execute()
            .path("teams.page").entity(Int::class.java).isEqualTo(0)
            .path("teams.size").entity(Int::class.java).isEqualTo(1)
            .path("teams.content[*].name").entityList(String::class.java).containsExactly("team-1")

        graphQlTester.document("""query { teams(size: 5000) { size } }""")
            .execute()
            .path("teams.size").entity(Int::class.java).isEqualTo(100)
    }

    @Test
    fun `updates a team name`() {
        val team = repository.save(Team(name = "platform"))

        graphQlTester.document("""mutation { updateTeam(id: ${team.id}, input: { name: "core" }) { name } }""")
            .execute().path("updateTeam.name").entity(String::class.java).isEqualTo("core")

        assertThat(repository.findAll().single().name).isEqualTo("core")
    }

    @Test
    fun `refuses to update a team onto an existing name`() {
        val team = repository.save(Team(name = "platform"))
        repository.save(Team(name = "research"))

        graphQlTester.document("""mutation { updateTeam(id: ${team.id}, input: { name: "research" }) { name } }""")
            .execute()
            .errors()
            .satisfy { errors ->
                assertThat(errors).singleElement()
                    .extracting { it.message }
                    .isEqualTo("""A team named "research" already exists""")
            }

        assertThat(repository.findByName("platform")).isNotNull()
        assertThat(auditRepository.findAll()).isEmpty()
    }

    @Test
    fun `refuses to update a team to a blank name`() {
        val team = repository.save(Team(name = "platform"))

        graphQlTester.document("""mutation { updateTeam(id: ${team.id}, input: { name: "  " }) { name } }""")
            .execute()
            .errors()
            .satisfy { errors ->
                assertThat(errors).singleElement()
                    .extracting { it.message }
                    .isEqualTo("A team name is required")
            }

        assertThat(repository.findAll().single().name).isEqualTo("platform")
    }

    @Test
    fun `records nothing when the name is unchanged`() {
        val team = repository.save(Team(name = "platform"))

        graphQlTester.document("""mutation { updateTeam(id: ${team.id}, input: { name: "platform" }) { name } }""")
            .execute().path("updateTeam.name").entity(String::class.java).isEqualTo("platform")

        assertThat(auditRepository.findAll()).isEmpty()
    }

    @Test
    fun `saves description and directory group from the settings form`() {
        val team = repository.save(Team(name = "backend"))

        graphQlTester.document(
            """
            mutation {
              updateTeam(id: ${team.id}, input: {
                name: "backend",
                description: "Core API, services, and data pipelines.",
                ldapGroup: "cn=backend,ou=teams,dc=gyloli,dc=io"
              }) { name description ldapGroup }
            }
            """,
        ).execute()
            .path("updateTeam.description").entity(String::class.java)
            .isEqualTo("Core API, services, and data pipelines.")
            .path("updateTeam.ldapGroup").entity(String::class.java)
            .isEqualTo("cn=backend,ou=teams,dc=gyloli,dc=io")

        val saved = repository.findAll().single()
        assertThat(saved.ldapGroup).isEqualTo("cn=backend,ou=teams,dc=gyloli,dc=io")
        // No rename, but the settings changes are still worth recording.
        assertThat(auditRepository.findAll().map { it.message })
            .containsExactlyInAnyOrder("Team LDAP group updated", "Team description updated")
    }

    @Test
    fun `clears the directory group when the field is emptied`() {
        val team = repository.save(Team(name = "backend", ldapGroup = "cn=backend,ou=teams,dc=gyloli,dc=io"))

        graphQlTester.document(
            """mutation { updateTeam(id: ${team.id}, input: { name: "backend", ldapGroup: "  " }) { ldapGroup } }""",
        ).execute().path("updateTeam.ldapGroup").valueIsNull()

        assertThat(repository.findAll().single().ldapGroup).isNull()
    }

    @Test
    fun `deletes a team and reports whether it existed`() {
        val team = repository.save(Team(name = "platform"))

        graphQlTester.document("""mutation { deleteTeam(id: ${team.id}) }""")
            .execute().path("deleteTeam").entity(Boolean::class.java).isEqualTo(true)

        graphQlTester.document("""mutation { deleteTeam(id: ${team.id}) }""")
            .execute().path("deleteTeam").entity(Boolean::class.java).isEqualTo(false)

        assertThat(repository.findAll()).isEmpty()
    }

    @Test
    fun `returns null for an unknown team`() {
        graphQlTester.document("""query { team(id: 999999) { name } }""")
            .execute().path("team").valueIsNull()
    }

    @Test
    fun `records an audit entry for every mutation, attributed to the caller`() {
        val id = graphQlTester.document("""mutation { createTeam(input: { name: "platform" }) { id } }""")
            .execute().path("createTeam.id").entity(String::class.java).get()

        graphQlTester.document("""mutation { updateTeam(id: $id, input: { name: "core" }) { name } }""").execute()
        graphQlTester.document("""mutation { deleteTeam(id: $id) }""").execute()

        // Integration entries ride along with a team's life; the lifecycle is what
        // this asserts.
        val entries = auditRepository.findAll().filter { it.operationType != null }.sortedBy { it.id }

        assertThat(entries.map { it.operationType }).containsExactly(
            TeamOperationType.ADD,
            TeamOperationType.RENAME,
            TeamOperationType.REMOVE,
        )
        assertThat(entries.map { it.userId }).containsOnly("alice")
        assertThat(entries.map { it.teamId }).containsOnly(id.toLong())
        assertThat(entries[0].oldTeamName).isNull()
        assertThat(entries[0].newTeamName).isEqualTo("platform")
        assertThat(entries[1].oldTeamName).isEqualTo("platform")
        assertThat(entries[1].newTeamName).isEqualTo("core")
        assertThat(entries[2].oldTeamName).isEqualTo("core")
        assertThat(entries[2].newTeamName).isNull()
    }

    @Test
    fun `does not record an audit entry when nothing was deleted`() {
        graphQlTester.document("""mutation { deleteTeam(id: 999999) }""")
            .execute().path("deleteTeam").entity(Boolean::class.java).isEqualTo(false)

        assertThat(auditRepository.findAll()).isEmpty()
    }
}
