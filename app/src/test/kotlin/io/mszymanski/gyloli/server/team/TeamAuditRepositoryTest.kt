package io.mszymanski.gyloli.server.team

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import java.time.OffsetDateTime

@SpringBootTest
class TeamAuditRepositoryTest(
    @Autowired val repository: TeamAuditRepository,
) {

    @BeforeEach
    fun clearAudit() = repository.deleteAll()

    @Test
    fun `stores one entry per operation type`() {
        val now = OffsetDateTime.now()

        repository.saveAll(
            listOf(
                TeamAudit(
                    teamId = 1,
                    newTeamName = "platform",
                    message = "seeded",
        operationType = TeamOperationType.ADD,
                    date = now.minusMinutes(2),
                    userId = "alice",
                ),
                TeamAudit(
                    teamId = 1,
                    oldTeamName = "platform",
                    newTeamName = "core",
                    message = "seeded",
                    operationType = TeamOperationType.RENAME,
                    date = now.minusMinutes(1),
                    userId = "alice",
                ),
                TeamAudit(
                    teamId = 1,
                    oldTeamName = "core",
                    message = "seeded",
                    operationType = TeamOperationType.REMOVE,
                    date = now,
                    userId = "bob",
                ),
            ),
        )

        val entries = repository.findByTeamId(1, newestFirst).content

        assertThat(entries).hasSize(3)
        assertThat(entries.map { it.operationType }).containsExactly(
            TeamOperationType.REMOVE,
            TeamOperationType.RENAME,
            TeamOperationType.ADD,
        )
        assertThat(entries.first().oldTeamName).isEqualTo("core")
        assertThat(entries.first().newTeamName).isNull()
        assertThat(entries.last().oldTeamName).isNull()
        assertThat(entries.map { it.id }).doesNotContainNull()
    }

    @Test
    fun `reads back only the requested team`() {
        repository.saveAll(
            listOf(
                TeamAudit(
                    teamId = 1,
                    newTeamName = "platform",
                    message = "seeded",
        operationType = TeamOperationType.ADD,
                    date = OffsetDateTime.now(),
                    userId = "alice",
                ),
                TeamAudit(
                    teamId = 2,
                    newTeamName = "research",
                    message = "seeded",
        operationType = TeamOperationType.ADD,
                    date = OffsetDateTime.now(),
                    userId = "alice",
                ),
            ),
        )

        assertThat(repository.findByTeamId(2, newestFirst).content.single().newTeamName).isEqualTo("research")
    }

    private val newestFirst = PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "date"))
}
