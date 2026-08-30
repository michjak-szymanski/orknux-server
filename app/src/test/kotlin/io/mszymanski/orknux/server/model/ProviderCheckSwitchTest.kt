package io.mszymanski.orknux.server.model

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.connector.model.ModelProvider
import io.mszymanski.orknux.connector.model.ModelProviderCheckProperties
import io.mszymanski.orknux.connector.model.ModelProviderMonitor
import io.mszymanski.orknux.connector.model.ModelProviderRepository
import io.mszymanski.orknux.connector.model.ModelService
import io.mszymanski.orknux.connector.model.ModelUsageRepository
import io.mszymanski.orknux.connector.model.ProviderStatus
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger

/**
 * A provider nobody wants polled.
 *
 * The timed sweep exists so that "Connected" on the Models screen means today
 * rather than the day somebody last pressed the button, and for a provider an
 * installation pays for that is right. It is wrong for the other kind: an
 * endpoint kept configured against a box that is only sometimes running - a
 * laptop's llama.cpp, a model server started for an afternoon. There the box
 * being off is the normal state, and every five minutes it produced a failed
 * row and a connection refused in the log about something nobody thought was
 * broken.
 *
 * So the switch, and the two halves of what it has to mean:
 *
 *  - **The sweep does not call it.** Asserted against a stub that counts, not
 *    against the status the check would have written - a provider that was
 *    called and happened to answer leaves the same row as one that was skipped,
 *    if the row is all you look at.
 *  - **Everything else still does.** Test Connection goes through
 *    [ModelService.testProvider] whatever the switch says, because a check
 *    somebody asked for is one they want the answer to. What is turned off is
 *    asking on their behalf.
 *
 * The sweep is driven through a monitor built here rather than the one the
 * context is running. That one is on a timer and also reacts to a provider
 * being saved, so a count taken against it would be counting whatever had
 * happened to fire - and the providers below are written straight to the
 * repository for the same reason, since saving through the service publishes
 * the event it listens for.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class ProviderCheckSwitchTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val service: ModelService,
    @Autowired val providers: ModelProviderRepository,
    @Autowired val models: LlmModelRepository,
    @Autowired val usage: ModelUsageRepository,
    @Autowired val workspaces: WorkspaceRepository,
) {

    private var workspaceId: Long = 0
    private lateinit var polled: Stub
    private lateinit var left: Stub

    @BeforeEach
    fun reset() {
        usage.deleteAll()
        models.deleteAll()
        providers.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
        polled = Stub()
        left = Stub()
    }

    @AfterEach
    fun stop() {
        polled.stop()
        left.stop()
    }

    @Test
    fun `the sweep calls the provider it is allowed to and leaves the other alone`() {
        stored("Cloud OpenAI", polled, checked = true)
        stored("The laptop", left, checked = false)

        monitor().sweep()

        assertThat(polled.asked.get())
            .describedAs("a provider left switched on is asked, which is the sweep working at all")
            .isEqualTo(1)
        assertThat(left.asked.get())
            .describedAs("and the one switched off is not called, which is the whole point")
            .isZero()
    }

    /**
     * The other half. A switch that also stopped the button would not be "do not
     * poll this", it would be "this provider is off" - and there is already a
     * way to say that, which is to remove it.
     */
    @Test
    fun `Test Connection still calls a provider the sweep is not allowed to`() {
        val id = stored("The laptop", left, checked = false)

        val checked = service.testProvider(id)

        assertThat(left.asked.get()).isEqualTo(1)
        assertThat(checked.status).isEqualTo(ProviderStatus.CONNECTED)
        assertThat(checked.checkEnabled)
            .describedAs("and checking it by hand does not turn the sweep back on")
            .isFalse()
    }

    /**
     * A provider that says nothing about it is checked, because that is what
     * every provider made before this column existed was doing and what
     * somebody adding one expects.
     */
    @Test
    fun `a provider created without an answer is one the sweep may call`() {
        val id = graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "Cloud OpenAI", endpoint: "${polled.root()}",
                 secret: "sk-anything"
               }) { id checkEnabled } }""",
        ).execute()
            .path("createModelProvider.checkEnabled").entity(Boolean::class.java).isEqualTo(true)
            .path("createModelProvider.id").entity(Long::class.java).get()

        assertThat(requireNotNull(providers.findById(id).orElse(null)).checkEnabled).isTrue()
    }

    @Test
    fun `the switch is set when a provider is added and changed afterwards`() {
        val id = graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "The laptop", endpoint: "${left.root()}",
                 secret: "sk-anything", checkEnabled: false
               }) { id checkEnabled } }""",
        ).execute()
            .path("createModelProvider.checkEnabled").entity(Boolean::class.java).isEqualTo(false)
            .path("createModelProvider.id").entity(Long::class.java).get()

        graphQlTester.document(
            """mutation { updateModelProvider(id: $id, input: {
                 name: "The laptop", endpoint: "${left.root()}", checkEnabled: true
               }) { checkEnabled } }""",
        ).execute().path("updateModelProvider.checkEnabled").entity(Boolean::class.java).isEqualTo(true)

        assertThat(requireNotNull(providers.findById(id).orElse(null)).checkEnabled).isTrue()
    }

    /**
     * An update that does not mention it leaves it alone.
     *
     * Unlike the endpoint or the region, which the form owns and sends every
     * time. A caller written before this column existed - a script, an older
     * interface - sends neither, and reading that silence as "true" would turn
     * the sweep back on for the one provider somebody deliberately turned it off
     * for, at the next unrelated edit.
     */
    @Test
    fun `an update that says nothing about the switch does not turn it back on`() {
        val id = stored("The laptop", left, checked = false)

        graphQlTester.document(
            """mutation { updateModelProvider(id: $id, input: {
                 name: "The laptop, renamed", endpoint: "${left.root()}"
               }) { name checkEnabled } }""",
        ).execute()
            .path("updateModelProvider.name").entity(String::class.java).isEqualTo("The laptop, renamed")
            .path("updateModelProvider.checkEnabled").entity(Boolean::class.java).isEqualTo(false)
    }

    /** Built here, so a count is of this sweep and nothing else. See the class note. */
    private fun monitor() = ModelProviderMonitor(providers, service, ModelProviderCheckProperties())

    /** Written straight to the repository: saving through the service publishes an event. */
    private fun stored(name: String, stub: Stub, checked: Boolean): Long = requireNotNull(
        providers.save(
            ModelProvider(
                workspaceId = workspaceId,
                name = name,
                endpoint = stub.root(),
                secret = "sk-anything",
                checkEnabled = checked,
            ),
        ).id,
    )

    /** An endpoint that answers with one model and counts how often it was asked. */
    private class Stub {
        val asked = AtomicInteger()
        private val server: HttpServer =
            HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)

        init {
            server.createContext("/models") { exchange ->
                asked.incrementAndGet()
                answer(exchange, """{"data":[{"id":"gpt-4o"}]}""")
            }
            server.start()
        }

        fun root(): String = "http://127.0.0.1:${server.address.port}"

        fun stop() = server.stop(0)

        private fun answer(exchange: HttpExchange, body: String) {
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }
}
