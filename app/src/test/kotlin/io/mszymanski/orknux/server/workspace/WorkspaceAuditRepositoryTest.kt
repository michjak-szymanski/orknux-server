package io.mszymanski.orknux.server.workspace

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import java.time.OffsetDateTime

@SpringBootTest
class WorkspaceAuditRepositoryTest(
    @Autowired val repository: WorkspaceAuditRepository,
) {

    @BeforeEach
    fun clearAudit() = repository.deleteAll()

    @Test
    fun `stores one entry per operation type`() {
        val now = OffsetDateTime.now()

        repository.saveAll(
            listOf(
                WorkspaceAudit(
                    workspaceId = 1,
                    newWorkspaceName = "platform",
                    message = "seeded",
        operationType = WorkspaceOperationType.ADD,
                    date = now.minusMinutes(2),
                    userId = "alice",
                ),
                WorkspaceAudit(
                    workspaceId = 1,
                    oldWorkspaceName = "platform",
                    newWorkspaceName = "core",
                    message = "seeded",
                    operationType = WorkspaceOperationType.RENAME,
                    date = now.minusMinutes(1),
                    userId = "alice",
                ),
                WorkspaceAudit(
                    workspaceId = 1,
                    oldWorkspaceName = "core",
                    message = "seeded",
                    operationType = WorkspaceOperationType.REMOVE,
                    date = now,
                    userId = "bob",
                ),
            ),
        )

        val entries = repository.findByWorkspaceId(1, newestFirst).content

        assertThat(entries).hasSize(3)
        assertThat(entries.map { it.operationType }).containsExactly(
            WorkspaceOperationType.REMOVE,
            WorkspaceOperationType.RENAME,
            WorkspaceOperationType.ADD,
        )
        assertThat(entries.first().oldWorkspaceName).isEqualTo("core")
        assertThat(entries.first().newWorkspaceName).isNull()
        assertThat(entries.last().oldWorkspaceName).isNull()
        assertThat(entries.map { it.id }).doesNotContainNull()
    }

    @Test
    fun `reads back only the requested workspace`() {
        repository.saveAll(
            listOf(
                WorkspaceAudit(
                    workspaceId = 1,
                    newWorkspaceName = "platform",
                    message = "seeded",
        operationType = WorkspaceOperationType.ADD,
                    date = OffsetDateTime.now(),
                    userId = "alice",
                ),
                WorkspaceAudit(
                    workspaceId = 2,
                    newWorkspaceName = "research",
                    message = "seeded",
        operationType = WorkspaceOperationType.ADD,
                    date = OffsetDateTime.now(),
                    userId = "alice",
                ),
            ),
        )

        assertThat(repository.findByWorkspaceId(2, newestFirst).content.single().newWorkspaceName).isEqualTo("research")
    }

    private val newestFirst = PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "date"))
}
