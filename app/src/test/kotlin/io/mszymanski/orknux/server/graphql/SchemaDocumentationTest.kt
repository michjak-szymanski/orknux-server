package io.mszymanski.orknux.server.graphql

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.readLines

/**
 * Documentation in the schema is attached to something.
 *
 * A field or a type inserted *between* a `"""doc"""` block and the thing that
 * block describes leaves the doc attached to nothing. graphql-java refuses the
 * whole schema for it — `Invalid syntax with offending token '"""'` and the
 * first line of the orphaned comment — which names the doc rather than the edit,
 * and only ever surfaces as a Spring context that will not start. That is a
 * minute and a half of build and boot to be told a paragraph is in the wrong
 * place, and it happened three times in one sitting.
 *
 * So it is asked here, in a test with no context to load, in a message that says
 * where the edit went wrong.
 *
 * Deliberately only this one shape. Whether every type resolves and every field
 * has a resolver is what starting the real schema answers, and a second
 * implementation of those rules here would be a second thing to keep in step.
 */
class SchemaDocumentationTest {

    @Test
    fun `every documentation block describes something`() {
        val lines = SCHEMA.readLines()
        val orphans = mutableListOf<String>()

        var openedAt: Int? = null
        for ((at, line) in lines.withIndex()) {
            val fences = FENCE.findAll(line).count()
            // A one-line `"""doc"""` opens and closes on the same line.
            if (fences == 0 || fences == 2) continue
            if (openedAt == null) {
                openedAt = at
                continue
            }

            /*
             * A block has just closed. What follows has to be something a doc
             * can describe, and never another doc — which is exactly the shape
             * left behind when a declaration is inserted above the wrong line.
             */
            val next = lines.drop(at + 1).indexOfFirst { it.isNotBlank() }
            val following = if (next == -1) "" else lines[at + 1 + next].trim()
            if (following.startsWith("\"")) {
                orphans += "line ${at + 1}: the block starting \"${lines[openedAt + 1].trim().take(60)}\" " +
                    "is followed by more documentation, so it describes nothing"
            }
            openedAt = null
        }

        /*
         * And the same shape one line high: a one-line `"doc"` followed by more
         * documentation. It is the commoner of the two here, because most of
         * this schema's short descriptions are written that way - and it is the
         * one that got past the first version of this test.
         */
        for ((at, line) in lines.withIndex()) {
            val text = line.trim()
            if (!ONE_LINE.matches(text)) continue
            val next = lines.drop(at + 1).indexOfFirst { it.isNotBlank() }
            val following = if (next == -1) "" else lines[at + 1 + next].trim()
            if (following.startsWith("\"")) {
                orphans += "line ${at + 1}: ${text.take(60)} is followed by more documentation, " +
                    "so it describes nothing"
            }
        }

        assertThat(openedAt).describedAs("a block string is never closed").isNull()
        assertThat(orphans)
            .describedAs("documentation has to sit directly above what it describes")
            .isEmpty()
    }

    private companion object {
        val SCHEMA: Path = Path.of("src/main/resources/graphql/schema.graphqls")
        val FENCE = Regex("\"\"\"")

        /** `"one line of documentation"`, which is how most of this schema writes a short one. */
        val ONE_LINE = Regex("^\"[^\"].*[^\"]\"$")
    }
}
