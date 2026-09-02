package io.mszymanski.orknux.server.plugin

import io.mszymanski.orknux.workflow.script.PluginCapability
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.test.context.support.WithMockUser
import java.io.File

/**
 * What a plugin author's editor is told.
 *
 * The template is the contract: a plugin is written outside this product, in
 * somebody's own editor, against the declarations at the top of the file they
 * download. So anything a plugin may call has to be *in* that file — a call the
 * server accepts and the template does not declare is a call nobody can discover
 * and every editor underlines.
 *
 * The placeholders are what this is really about. They are filled in from the
 * enumerations that do the judging, so the template cannot come to describe a
 * contract different from the one that will refuse it — and a placeholder left
 * unsubstituted would ship `@PERMISSION_UNION@` as a type name, which compiles
 * nowhere.
 *
 * Written to `target` as well, so the TypeScript in it can be compiled by hand
 * when the declarations change: `npx tsc --noEmit app/target/orknux-plugin.ts`.
 */
@SpringBootTest
@WithMockUser(username = "alice", roles = ["ADMINS"])
class PluginTemplateTest(@Autowired val plugins: PluginUploadAPI) {

    private val template: String get() = requireNotNull(plugins.template().body)

    @Test
    fun `nothing in the template is left as a placeholder`() {
        // `@SOMETHING@` is how every substitution here is written, so one that
        // survives is one nobody wired up.
        assertThat(Regex("@[A-Z_]+@").findAll(template).map { it.value }.toList()).isEmpty()
    }

    /**
     * The one door out of the sandbox, declared where a plugin author will see it.
     *
     * A capability the server accepts and the template does not describe is a
     * capability nobody can find and no editor will complete.
     */
    @Test
    fun `what the server will do on a plugin's behalf is declared`() {
        assertThat(template).contains("declare const orknux")
        assertThat(template).contains("thread(")
        assertThat(template).contains("SlackThread")
    }

    /**
     * A Slack connection is not a Jira one.
     *
     * The type parameter has to reach a member for that to be true of the types
     * as well as of the runtime - two aliases over an id alone would be
     * interchangeable, and the mistake would be found at the first call rather
     * than where it was written.
     */
    @Test
    fun `a connection carries its kind in the type`() {
        assertThat(template).contains("declare class OrknuxConnection<T extends ConnectionType>")
        assertThat(template).contains("readonly type: T;")
        assertThat(template).contains("type SlackConnection = OrknuxConnection<'SLACK'>")
    }

    /** Every capability this server has, named in the file somebody writes against. */
    @Test
    fun `the capabilities a plugin may ask for are all described`() {
        PluginCapability.entries.forEach { capability ->
            assertThat(template)
                .describedAs("the template says nothing about %s", capability.name)
                .contains(capability.name)
        }
    }

    /** Kept so the declarations can be run through a compiler when they change. */
    @Test
    fun `the template is written out to be compiled by hand`() {
        val written = File("target/orknux-plugin.ts")
        written.writeText(template)
        assertThat(written).exists()
    }
}
