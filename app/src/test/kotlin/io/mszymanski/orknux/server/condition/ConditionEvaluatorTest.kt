package io.mszymanski.orknux.server.condition

import io.mszymanski.orknux.server.action.ValueType
import io.mszymanski.orknux.server.action.WorkflowActionRepository
import io.mszymanski.orknux.server.action.WorkflowFunction
import io.mszymanski.orknux.server.action.WorkflowFunctionRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import tools.jackson.databind.ObjectMapper

/**
 * A condition that asks a function: what the sandbox is told, and what counts
 * as an answer.
 */
@SpringBootTest
class ConditionEvaluatorTest(
    @Autowired val evaluator: ConditionEvaluator,
    @Autowired val actions: WorkflowActionRepository,
    @Autowired val conditions: WorkflowConditionRepository,
    @Autowired val functions: WorkflowFunctionRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val mapper: ObjectMapper,
) {

    private var workspaceId: Long = 0

    @BeforeEach
    fun reset() {
        // Whatever holds a function goes first: an action or a condition may name one.
        actions.deleteAll()
        conditions.deleteAll()
        functions.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
    }

    @Test
    fun `the asking function is told when now is, since the sandbox has no clock of its own`() {
        val condition = condition(
            "is this century",
            """
            export default async function isThisCentury() {
              // `context` is what the run knows about where this is running.
              return new Date(context.now).getUTCFullYear() > 2000;
            }
            """.trimIndent(),
        )

        assertThat(evaluator.holds(condition, null)).isTrue()
    }

    @Test
    fun `the function is handed what the run is carrying`() {
        val condition = condition(
            "is urgent",
            """
            export default async function isUrgent(input) {
              return input.priority === 'high';
            }
            """.trimIndent(),
        )

        assertThat(evaluator.holds(condition, """{"priority":"high"}""")).isTrue()
        assertThat(evaluator.holds(condition, """{"priority":"low"}""")).isFalse()
    }

    @Test
    fun `negate turns the answer round`() {
        val condition = condition(
            "never",
            "export default async function never() {\n  return false;\n}",
        )
        condition.negate = true
        conditions.save(condition)

        assertThat(evaluator.holds(condition, null)).isTrue()
    }

    @Test
    fun `an answer that is neither true nor false is refused rather than guessed at`() {
        val condition = condition(
            "maybe",
            "export default async function maybe() {\n  return 'maybe';\n}",
        )

        assertThatThrownBy { evaluator.holds(condition, null) }
            .isInstanceOf(ConditionNotDecidableException::class.java)
            .hasMessageContaining("which is not true or false")
    }

    @Test
    fun `a pattern is run against a value of the size a real message is`() {
        val condition = matching("looks like a ticket", """[A-Z]{2,10}-\d+""")

        assertThat(evaluator.holds(condition, """{"text":"please look at ORKX-114 today"}""")).isTrue()
        assertThat(evaluator.holds(condition, """{"text":"please look at it today"}""")).isFalse()
        // Longer than anything a chat renders, and still under the bound.
        val long = "x".repeat(9_000) + " ORKX-114"
        assertThat(evaluator.holds(condition, mapper.writeValueAsString(mapOf("text" to long)))).isTrue()
    }

    @Test
    fun `a value past the bound is not matched, since that regex runs on this thread`() {
        val condition = matching("looks like a ticket", """[A-Z]{2,10}-\d+""")

        // The pattern would match; the value is more than MATCHES will take from
        // whoever sent it, so the answer is no rather than however long it takes.
        val enormous = "x".repeat(20_000) + " ORKX-114"
        assertThat(evaluator.holds(condition, mapper.writeValueAsString(mapOf("text" to enormous)))).isFalse()
    }

    private fun matching(name: String, pattern: String): WorkflowCondition = conditions.save(
        WorkflowCondition(
            workspaceId = workspaceId,
            name = name,
            type = ConditionType.SLACK,
            property = ConditionProperty.MESSAGE_TEXT,
            check = ConditionCheck.MATCHES,
            values = mutableListOf(pattern),
        ),
    )

    private fun condition(name: String, source: String): WorkflowCondition {
        val function = functions.save(
            WorkflowFunction(workspaceId = workspaceId, name = name.replace(" ", ""), source = source, returnType = ValueType.BOOLEAN),
        )
        return conditions.save(
            WorkflowCondition(
                workspaceId = workspaceId,
                name = name,
                type = ConditionType.FUNCTION,
                functionId = function.id,
            ),
        )
    }
}
