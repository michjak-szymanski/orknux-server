package io.mszymanski.orknux.workflow.script

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The one door a plugin has out of its sandbox.
 *
 * A plugin has no network and no way to ask for one — `IOAccess.NONE`, and a
 * permission list whose vocabulary cannot express a socket. But a plugin's whole
 * job is to know one outside service well, and there are questions about that
 * service which cannot be answered from a payload: what is in this Slack thread
 * is the one this was built for. So the server makes the call, under a named
 * grant, and what crosses is data. Issue #316.
 *
 * Five things this pins, and the first two are the security property:
 *
 *   ungranted   a plugin that was granted nothing is told so, in words, and the
 *               server is never asked. The helper exists and refuses; what is
 *               absent is the host behind it, which is the half that matters
 *   unaccepted  declaring is not being granted: what is bound is what was
 *               *granted*, never what the plugin asked for
 *   granted     one that was granted it can call it, and gets the answer back
 *   as data     what crosses is JSON both ways, so there is no host object on
 *               either side to walk from
 *   refusals    a refusal comes back as data rather than as a thrown error, so a
 *               plugin can say something useful about it
 */
class PluginCapabilityTest {

    private val asked = mutableListOf<Pair<PluginCapability, String>>()

    /** A server that answers, and writes down what it was asked. */
    private val host = PluginHost { capability, argument, _ ->
        asked += capability to argument
        """{"messages":[{"ts":"1.1","text":"hello"}],"replies":1}"""
    }

    private val runner = PluginRunner(
        PluginProperties(timeoutMillis = 5_000, statementLimit = 2_000_000),
        host,
    )

    /** A plugin that calls the capability and hands back whatever it got. */
    private val source = """
        export default class Reader extends OrknuxPlugin {
          id() { return 'reader'; }
          apiVersion() { return 1; }
          capabilities() { return ['SLACK_READ_THREAD']; }
          functions() {
            return [new OrknuxFunction({
              name: 'read',
              params: [{ name: 'channel', type: 'string' }],
              returnType: 'map',
              run: (channel) => orknux.slack.thread({ id: 7, type: 'SLACK' }, channel, '1.0'),
            })];
          }
        }
    """.trimIndent()

    @Test
    fun `a plugin granted nothing has no way to reach the server`() {
        val answer = runner.call(source, "read", listOf("\"#general\""), capabilities = emptySet())

        /*
         * Refused in words rather than by throwing whatever a call on undefined
         * throws - but the property that matters is the second assertion: the
         * host was never reached, so there was nothing to refuse *with*.
         */
        assertThat(answer).isInstanceOf(ScriptResult.Returned::class.java)
        assertThat((answer as ScriptResult.Returned).json).contains("was not granted SLACK_READ_THREAD")
        assertThat(asked).describedAs("the server was never asked").isEmpty()
    }

    /**
     * The half that matters most: what is bound is what was granted.
     *
     * A plugin declaring a capability is a plugin asking. Binding on the strength
     * of the declaration would make the acceptance decorative — which is the one
     * failure this whole arrangement exists to prevent.
     */
    @Test
    fun `declaring a capability is not being granted it`() {
        val answer = runner.call(source, "read", listOf("\"#general\""), capabilities = emptySet())

        assertThat((answer as ScriptResult.Returned).json).contains("was not granted SLACK_READ_THREAD")
        assertThat(asked).describedAs("the host was never asked").isEmpty()
    }

    @Test
    fun `a plugin granted one can call it, and gets the answer`() {
        val answer = runner.call(
            source,
            "read",
            listOf("\"#general\""),
            capabilities = setOf(PluginCapability.SLACK_READ_THREAD),
        )

        assertThat((answer as ScriptResult.Returned).json).contains("\"replies\":1")
        assertThat(asked).singleElement().satisfies({ (capability, argument) ->
            assertThat(capability).isEqualTo(PluginCapability.SLACK_READ_THREAD)
            // The connection is handed over as its id, and everything crosses as
            // JSON: no host object on either side to walk from.
            assertThat(argument).isEqualTo("""[7,"#general","1.0",null]""")
        })
    }

    /**
     * A plugin's `orknux.http` is a function's, member for member.
     *
     * They were written out twice - once in each runner - and the copies had
     * drifted the moment functions were given HTTP: the plugin side kept an
     * older shape with no `post` and no parsed `json`, so the same call written
     * in the two places answered differently. Two copies of an API is two APIs,
     * so there is one now, and this is what says so.
     */
    @Test
    fun `a plugin gets the same http helper a function does`() {
        val answering = PluginRunner(
            PluginProperties(timeoutMillis = 5_000, statementLimit = 2_000_000),
            PluginHost { capability, argument, _ ->
                asked += capability to argument
                """{"status":201,"headers":{},"body":"{\"id\":9}"}"""
            },
        )

        val poster = """
            export default class Poster extends OrknuxPlugin {
              id() { return 'poster'; }
              apiVersion() { return 1; }
              capabilities() { return ['NETWORK_REQUEST']; }
              functions() {
                return [new OrknuxFunction({
                  name: 'make',
                  params: [{ name: 'url', type: 'string' }],
                  returnType: 'map',
                  run: (url) => {
                    const r = orknux.http.post(url, { title: 'hello' });
                    return { id: r.json.id, status: r.status };
                  },
                })];
              }
            }
        """.trimIndent()

        val answer = answering.call(
            poster,
            "make",
            listOf("\"https://api.example.com/tickets\""),
            capabilities = setOf(PluginCapability.NETWORK_REQUEST),
        )

        // `post` exists, an object body went out as JSON with the header, and
        // the JSON reply came back parsed.
        assertThat((answer as ScriptResult.Returned).json).contains("\"id\":9")
        assertThat(answer.json).contains("\"status\":201")
        assertThat(asked.single().second).contains("application/json")
        assertThat(asked.single().second).contains("title")
    }

    /**
     * A refusal is data.
     *
     * A plugin has to be able to say something useful about "that connection is
     * gone", and an exception here would surface as a plugin that failed for
     * reasons nobody can read.
     */
    @Test
    fun `a refusal from the server arrives as an answer rather than as a failure`() {
        val refusing = PluginRunner(
            PluginProperties(timeoutMillis = 5_000, statementLimit = 2_000_000),
            { _, _, _ -> """{"error":"that connection has been deleted"}""" },
        )

        val answer = refusing.call(
            source,
            "read",
            listOf("\"#general\""),
            capabilities = setOf(PluginCapability.SLACK_READ_THREAD),
        )

        assertThat(answer).isInstanceOf(ScriptResult.Returned::class.java)
        assertThat((answer as ScriptResult.Returned).json).contains("that connection has been deleted")
    }

    /** What it declares is readable, so a person can be shown it before granting. */
    @Test
    fun `what a plugin asks the server for is read off it`() {
        val read = runner.inspect(source)

        assertThat(read).isInstanceOf(PluginInspection.Read::class.java)
        assertThat((read as PluginInspection.Read).capabilities).containsExactly("SLACK_READ_THREAD")
    }
}
