package io.mszymanski.orknux.server.revision

import io.mszymanski.orknux.server.attachment.InstallationSettingRepository
import io.mszymanski.orknux.server.attachment.InstallationSettings
import io.mszymanski.orknux.server.workflow.Workflow
import io.mszymanski.orknux.server.workflow.WorkflowPublication
import io.mszymanski.orknux.server.workflow.WorkflowPublicationRepository
import io.mszymanski.orknux.server.workflow.WorkflowRepository
import io.mszymanski.orknux.server.workflow.WorkspaceWorkflowRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
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
 * A revision per save with no rule is a table nobody prunes.
 *
 * So there is a rule, it is a real setting on the Admin screen rather than a
 * constant, and this is what proves something honours it. Fourteen days is the
 * default the owner chose; the sweep reads the setting on every pass, so
 * changing it takes effect without a restart.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class RevisionRetentionTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val sweeper: RevisionSweeper,
    @Autowired val revisions: ComponentRevisionRepository,
    @Autowired val publications: WorkflowPublicationRepository,
    @Autowired val workflows: WorkflowRepository,
    @Autowired val assignments: WorkspaceWorkflowRepository,
    @Autowired val settings: InstallationSettings,
    @Autowired val storedSettings: InstallationSettingRepository,
    @Autowired val workspaces: WorkspaceRepository,
) {

    private var workspaceId: Long = 0

    @BeforeEach
    fun reset() {
        revisions.deleteAll()
        publications.deleteAll()
        assignments.deleteAll()
        workflows.deleteAll()
        storedSettings.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "support")).id)
    }

    /** Fourteen days, until somebody says otherwise. */
    @Test
    fun `the default is a fortnight`() {
        assertThat(settings.revisionRetentionDays()).isEqualTo(14)
        graphQlTester.document("""query { installationSettings { revisionRetentionDays } }""").execute()
            .path("installationSettings.revisionRetentionDays").entity(Int::class.java).isEqualTo(14)
    }

    /**
     * It measures from when a state stopped being current, not from when it was
     * written: a prompt composed a year ago and replaced this morning is a
     * fortnight of history to come.
     */
    @Test
    fun `the sweep takes history older than the setting and leaves the rest`() {
        val stale = record(recordedAt = OffsetDateTime.now().minusDays(20))
        val recent = record(recordedAt = OffsetDateTime.now().minusDays(3))

        assertThat(sweeper.sweep()).isEqualTo(1)
        assertThat(revisions.findAll().map { it.id }).containsExactly(recent)
        assertThat(revisions.findById(stale)).isEmpty()
    }

    /** Changed on the screen, honoured on the next pass and not on a restart. */
    @Test
    fun `an administrator can change how long history is kept`() {
        val old = record(recordedAt = OffsetDateTime.now().minusDays(20))

        graphQlTester.document("""mutation { setRevisionRetentionDays(days: 90) { revisionRetentionDays } }""")
            .execute()
            .path("setRevisionRetentionDays.revisionRetentionDays").entity(Int::class.java).isEqualTo(90)

        assertThat(sweeper.sweep()).isEqualTo(0)
        assertThat(revisions.findById(old)).isPresent()
    }

    /** A number is not the way to say "keep nothing", so zero is refused. */
    @Test
    fun `a retention outside what a screen would offer is refused`() {
        graphQlTester.document("""mutation { setRevisionRetentionDays(days: 0) { revisionRetentionDays } }""")
            .execute().errors().satisfy { errors ->
                assertThat(errors).singleElement()
                    .satisfies({ assertThat(it.message).contains("not a number of days") })
            }
    }

    /**
     * The one row age must never reach.
     *
     * A workflow's newest publication is not history: it is what the workflow
     * runs. Sweeping it because it is old would stop a workflow that nobody had
     * touched, which is the worst failure available here — silent, and days
     * after the change that caused it.
     */
    @Test
    fun `a workflow's live publication survives however old it is`() {
        val workflow = workflows.save(Workflow(name = "Answer the customer"))
        val workflowId = requireNotNull(workflow.id)
        val ancient = publications.save(
            WorkflowPublication(
                workflowId = workflowId,
                publishedAt = OffsetDateTime.now().minusYears(2),
                publishedBy = "alice",
                graph = "{}",
            ),
        )
        val superseded = publications.save(
            WorkflowPublication(
                workflowId = workflowId,
                publishedAt = OffsetDateTime.now().minusYears(1),
                publishedBy = "alice",
                graph = "{}",
            ),
        )

        // The newer of the two is what runs, so the older one is history and
        // goes; the live one stays despite being a year old.
        assertThat(sweeper.sweep()).isEqualTo(1)
        assertThat(publications.findById(requireNotNull(ancient.id))).isEmpty()
        assertThat(publications.findById(requireNotNull(superseded.id))).isPresent()
    }

    private fun record(recordedAt: OffsetDateTime): Long = requireNotNull(
        revisions.save(
            ComponentRevision(
                workspaceId = workspaceId,
                kind = ComponentRevisionKind.TOOL,
                componentId = 1,
                name = "forecast",
                savedAt = recordedAt,
                savedBy = "alice",
                recordedAt = recordedAt,
                snapshot = """{"version":1,"name":"forecast"}""",
            ),
        ).id,
    )
}
