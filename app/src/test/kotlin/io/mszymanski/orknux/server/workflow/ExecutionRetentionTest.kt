package io.mszymanski.orknux.server.workflow

import io.mszymanski.orknux.server.attachment.InstallationSettingRepository
import io.mszymanski.orknux.server.attachment.InstallationSettings
import io.mszymanski.orknux.server.attachment.SettingNames
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import io.mszymanski.orknux.workflow.execution.ExecutionLog
import io.mszymanski.orknux.workflow.execution.ExecutionLogRepository
import io.mszymanski.orknux.workflow.execution.ExecutionStatus
import io.mszymanski.orknux.workflow.execution.ExecutionStep
import io.mszymanski.orknux.workflow.execution.ExecutionStepRepository
import io.mszymanski.orknux.workflow.execution.ExecutionTrigger
import io.mszymanski.orknux.workflow.execution.NodeKind
import io.mszymanski.orknux.workflow.execution.WorkflowExecution
import io.mszymanski.orknux.workflow.execution.WorkflowExecutionRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.test.context.support.WithMockUser
import java.time.OffsetDateTime

/**
 * Nothing deleted a run, ever. Now something does, and only what it should.
 *
 * There was no `deleteExecution`, no retention and no cascade - `workflow_execution`
 * carries no foreign key on the workspace or the workflow, because those tables
 * belong to another module - so the table grew without bound and 13% of one
 * workspace's runs belonged to workflows it no longer listed. Issue #167.
 *
 * Four things, and the second is the one that makes the rest safe:
 *
 *   the sweep    a run that finished longer ago than the setting says is taken,
 *                and its steps and log lines go with it
 *   still going  a run that has not finished is never taken, whatever its age
 *   the setting  read on every pass, so a change takes effect without a restart
 *   the forget   deleting a workspace takes its runs, which nothing else could
 *                reach afterwards
 */
@SpringBootTest
@WithMockUser(username = "alice", roles = ["ADMINS"])
class ExecutionRetentionTest(
    @Autowired val sweeper: ExecutionSweeper,
    @Autowired val executions: WorkflowExecutionRepository,
    @Autowired val steps: ExecutionStepRepository,
    @Autowired val logs: ExecutionLogRepository,
    @Autowired val settings: InstallationSettings,
    @Autowired val storedSettings: InstallationSettingRepository,
    @Autowired val workspaces: WorkspaceRepository,
) {

    private var workspaceId: Long = 0

    /**
     * Its own workspace, and only its own setting put back.
     *
     * Nothing global is wiped: the sweep is installation-wide by nature, and
     * what makes these counts exact is that everything else in the suite writes
     * runs dated now while this writes the only old ones.
     */
    @BeforeEach
    fun reset() {
        storedSettings.findById(SettingNames.EXECUTION_RETENTION_DAYS).ifPresent { storedSettings.delete(it) }
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "retention ${System.nanoTime()}")).id)
    }

    @Test
    fun `a run that finished longer ago than the setting says is swept, and its steps with it`() {
        val old = finished(days = 120)
        val recent = finished(days = 3)

        // A step and a line on the old one, which nothing cascades away.
        steps.save(ExecutionStep(
                executionId = old,
                nodeKey = "one",
                kind = NodeKind.ACTION,
                name = "One",
                order = 0,
                x = 0.0,
                y = 0.0,
            ))
        logs.save(ExecutionLog(
                executionId = old,
                sequence = 0,
                message = "said something",
                loggedAt = OffsetDateTime.now(),
            ))

        assertThat(sweeper.sweep()).isGreaterThanOrEqualTo(1)

        assertThat(executions.findById(old)).isEmpty
        assertThat(executions.findById(recent)).isPresent
        assertThat(steps.findByExecutionIdOrderByOrderAsc(old)).isEmpty()
        assertThat(logs.findByExecutionIdOrderBySequenceAsc(old)).isEmpty()
    }

    /**
     * The one that must never be got wrong.
     *
     * A run still going has no `finishedAt`, so measuring from it excludes them
     * by construction - and the status is asked as well, so a run left
     * unfinished by a crash is not made immortal by the same null.
     */
    @Test
    fun `a run that has not finished is never swept, however old it is`() {
        val running = executions.save(
            WorkflowExecution(
                workspaceId = workspaceId,
                workflowId = 1,
                workflowName = "Long one",
                status = ExecutionStatus.RUNNING,
                trigger = ExecutionTrigger.MANUAL,
                startedAt = OffsetDateTime.now().minusDays(400),
            ),
        ).id

        sweeper.sweep()

        assertThat(executions.findById(requireNotNull(running))).isPresent
    }

    @Test
    fun `the setting decides, and is read on every pass`() {
        val id = finished(days = 30)

        // Ninety days by default, so thirty is kept.
        sweeper.sweep()
        assertThat(executions.findById(id)).isPresent

        settings.setExecutionRetentionDays(7, by = "alice")
        assertThat(settings.executionRetentionDays()).isEqualTo(7)

        sweeper.sweep()
        assertThat(executions.findById(id)).isEmpty
    }

    /**
     * A deleted workspace's runs are reachable by nothing, so they go with it.
     *
     * `deleteWorkspace` already had the precedent line for connections, for the
     * same no-foreign-key reason; this is the one it was missing.
     */
    @Test
    fun `deleting a workspace forgets its runs, whatever their age`() {
        val fresh = finished(days = 0)
        steps.save(ExecutionStep(
                executionId = fresh,
                nodeKey = "one",
                kind = NodeKind.ACTION,
                name = "One",
                order = 0,
                x = 0.0,
                y = 0.0,
            ))

        assertThat(sweeper.forgetWorkspace(workspaceId)).isGreaterThanOrEqualTo(1)

        assertThat(executions.findById(fresh)).isEmpty
        assertThat(steps.findByExecutionIdOrderByOrderAsc(fresh)).isEmpty()
    }

    /** Nothing to forget is not a failure: the caller may retry it. */
    @Test
    fun `forgetting a workspace with no runs does nothing and says so`() {
        assertThat(sweeper.forgetWorkspace(-1)).isEqualTo(0)
    }

    /** One finished run of this workspace, that long ago. */
    private fun finished(days: Long): Long {
        val at = OffsetDateTime.now().minusDays(days)
        return requireNotNull(
            executions.save(
                WorkflowExecution(
                    workspaceId = workspaceId,
                    workflowId = 1,
                    workflowName = "Ran",
                    status = ExecutionStatus.COMPLETED,
                    trigger = ExecutionTrigger.MANUAL,
                    startedAt = at,
                    finishedAt = at,
                ),
            ).id,
        )
    }
}
