package io.mszymanski.gyloli.server.condition

import io.mszymanski.gyloli.server.action.ValueType
import io.mszymanski.gyloli.server.action.WorkflowActionRepository
import io.mszymanski.gyloli.server.action.WorkflowFunction
import io.mszymanski.gyloli.server.action.WorkflowFunctionRepository
import io.mszymanski.gyloli.server.team.Team
import io.mszymanski.gyloli.server.team.TeamRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

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
    @Autowired val teams: TeamRepository,
) {

    private var teamId: Long = 0

    @BeforeEach
    fun reset() {
        // Whatever holds a function goes first: an action or a condition may name one.
        actions.deleteAll()
        conditions.deleteAll()
        functions.deleteAll()
        teams.deleteAll()
        teamId = requireNotNull(teams.save(Team(name = "backend")).id)
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

    private fun condition(name: String, source: String): WorkflowCondition {
        val function = functions.save(
            WorkflowFunction(teamId = teamId, name = name.replace(" ", ""), source = source, returnType = ValueType.BOOLEAN),
        )
        return conditions.save(
            WorkflowCondition(
                teamId = teamId,
                name = name,
                type = ConditionType.FUNCTION,
                functionId = function.id,
            ),
        )
    }
}
