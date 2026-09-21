package io.mszymanski.orknux.server.workflow

import io.mszymanski.orknux.server.obj.ObjectNodeRunner
import io.mszymanski.orknux.workflow.execution.ExecutionStep
import io.mszymanski.orknux.workflow.execution.NodeBinding
import io.mszymanski.orknux.workflow.execution.NodeKind
import io.mszymanski.orknux.workflow.execution.StepStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper

/**
 * A field an Object node names for itself arrives as what it says it is.
 *
 * Issue #359. The fields of a node with no saved shape were a name and a value,
 * so `3` typed into one arrived downstream as the string `"3"` and a later
 * condition asking whether it was greater than two was asking about text. What
 * the node declares is now what the run carries.
 *
 * Nothing is forced. A field left alone is a string, which is what every field
 * written before this was, and a field declared a number and left holding
 * `soon` keeps the text: somebody is mid-edit, and a run that dropped the field
 * or failed the step would lose work it can perfectly well carry.
 */
class ObjectFieldTypeTest {

    private val mapper = ObjectMapper()
    private val expressions = NodeExpressions(mapper)
    private val runner = ObjectNodeRunner(expressions, mapper)

    /** One step, carrying the fields as the planner writes them down. */
    private fun step(vararg fields: Pair<String, NodeBinding>): ExecutionStep = ExecutionStep(
        executionId = 1,
        nodeKey = "held",
        kind = NodeKind.OBJECT,
        name = "Ticket",
        outputName = "ticket",
        mappings = mapper.writeValueAsString(fields.toMap()),
        order = 0,
        x = 0.0,
        y = 0.0,
    )

    private fun made(step: ExecutionStep): String {
        val result = runner.run(step, input = null, trigger = null)
        assertThat(result.status).isEqualTo(StepStatus.COMPLETED)
        return result.output.orEmpty()
    }

    @Test
    fun `a field says what it holds, and the run carries that`() {
        val json = made(
            step(
                "count" to NodeBinding(expression = "3", type = "NUMBER"),
                "urgent" to NodeBinding(expression = "true", type = "BOOLEAN"),
                "title" to NodeBinding(expression = "The reply is late", type = "STRING"),
            ),
        )

        val held = mapper.readTree(json).path("ticket")
        assertThat(held.path("count").isNumber).isTrue()
        assertThat(held.path("count").asInt()).isEqualTo(3)
        assertThat(held.path("urgent").isBoolean).isTrue()
        assertThat(held.path("urgent").asBoolean()).isTrue()
        assertThat(held.path("title").isTextual).isTrue()
        assertThat(held.path("title").stringValue()).isEqualTo("The reply is late")
    }

    @Test
    fun `an object or a list is read as the shape it spells`() {
        val json = made(
            step(
                "who" to NodeBinding(expression = """{"name":"Bob"}""", type = "OBJECT"),
                "tags" to NodeBinding(expression = """["slack","timing"]""", type = "ARRAY"),
            ),
        )

        val held = mapper.readTree(json).path("ticket")
        assertThat(held.path("who").path("name").stringValue()).isEqualTo("Bob")
        assertThat(held.path("tags").isArray).isTrue()
        assertThat(held.path("tags").size()).isEqualTo(2)
    }

    /**
     * What every field written before this was, and what one nobody typed still
     * is: `3` is the characters `3` unless somebody said otherwise.
     */
    @Test
    fun `a field that says nothing is the text it holds`() {
        val json = made(step("count" to NodeBinding(expression = "3")))

        val held = mapper.readTree(json).path("ticket")
        assertThat(held.path("count").isTextual).isTrue()
        assertThat(held.path("count").stringValue()).isEqualTo("3")
    }

    /**
     * A number that is not one is kept rather than dropped.
     *
     * Half-typed, or meant: either way the run carries it and whoever opens the
     * step sees what is there. Losing the field, or failing the step over it,
     * would make a type an editing hazard rather than a declaration.
     */
    @Test
    fun `a value that does not spell its type is kept as the text it is`() {
        val json = made(
            step(
                "count" to NodeBinding(expression = "soon", type = "NUMBER"),
                "who" to NodeBinding(expression = "not json at all", type = "OBJECT"),
            ),
        )

        val held = mapper.readTree(json).path("ticket")
        assertThat(held.path("count").stringValue()).isEqualTo("soon")
        assertThat(held.path("who").stringValue()).isEqualTo("not json at all")
    }

    /** A reference is read from the run, and what it finds keeps its own shape. */
    @Test
    fun `a reference is read as it stands, whatever the field was declared`() {
        val carried = mapper.readTree("""{"score": 7}""")
        val step = step("count" to NodeBinding(expression = "score", reference = true, type = "NUMBER"))

        val result = runner.run(step, input = carried.toString(), trigger = null)

        val held = mapper.readTree(result.output.orEmpty()).path("ticket")
        assertThat(held.path("count").asInt()).isEqualTo(7)
    }
}
