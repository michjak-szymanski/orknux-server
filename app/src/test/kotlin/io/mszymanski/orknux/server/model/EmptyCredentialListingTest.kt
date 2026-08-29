package io.mszymanski.orknux.server.model

import io.mszymanski.orknux.connector.connection.ConnectionProbe
import io.mszymanski.orknux.connector.connection.ConnectionProperties
import io.mszymanski.orknux.connector.model.ModelClients
import io.mszymanski.orknux.connector.model.ModelProvider
import io.mszymanski.orknux.connector.model.ModelProviderProbe
import io.mszymanski.orknux.connector.model.ProviderType
import io.mszymanski.orknux.connector.proxy.ProxyRouter
import io.mszymanski.orknux.connector.proxy.ProxyRuleSource
import io.mszymanski.orknux.connector.security.HeldSecret
import io.mszymanski.orknux.connector.security.SecretCipher
import io.mszymanski.orknux.connector.security.SecretReferences
import io.mszymanski.orknux.connector.security.SecretVariables
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper

/**
 * What the card says when the credential is the thing that is wrong.
 *
 * A provider pointed at a workspace secret nobody has filled in yet cannot be
 * called, and the sentence it earns says so and names the variable. The one it
 * must not earn is "check the endpoint": that sends whoever configured it to
 * the one part of the configuration that is right, which is the confusion #211
 * existed to remove.
 *
 * This is asserted at [ModelProviderProbe.list] rather than through the screen
 * because the ordering it pins is not visible from there. Listing now asks the
 * SDK first and falls back to a hand-built request, and both roads resolve the
 * credential before either sends anything - so a credential that cannot be
 * resolved is a refusal, and a refusal is returned rather than being retried
 * somewhere else. Were the SDK asked first *without* that check, the call would
 * go out with nothing in it, fail as a connection failure, and be reported as
 * an endpoint that could not be reached - which is exactly the wrong afternoon.
 *
 * The endpoint here does not resolve, on purpose. If the credential is ever
 * read late, the DNS failure is what would be reported, and that is the failure
 * this test is watching for.
 */
class EmptyCredentialListingTest {

    @Test
    fun `a provider whose workspace secret is empty says so, and does not blame the endpoint`() {
        val listed = probe(blank = true).list(provider())

        val failed = listed as ModelProviderProbe.Listing.Failed
        assertThat(failed.reason)
            .describedAs("the sentence names the variable and what is wrong with it")
            .contains(VARIABLE_NAME)
            .contains("has no value")
        assertThat(failed.reason)
            .describedAs("and does not send anybody to look at the endpoint, which is right")
            .doesNotContain("check the endpoint")
    }

    /**
     * The other half of the same ordering: a variable that *is* filled in gets
     * as far as the call, so the check above is not passing because listing
     * refuses everything that reads a variable.
     */
    @Test
    fun `a provider whose workspace secret holds a value gets as far as calling the endpoint`() {
        val listed = probe(blank = false).list(provider())

        val failed = listed as ModelProviderProbe.Listing.Failed
        assertThat(failed.reason)
            .describedAs("nothing is wrong with the credential, so the failure is the unreachable host")
            .doesNotContain("has no value")
    }

    private fun probe(blank: Boolean): ModelProviderProbe {
        val variables = SecretVariables { _, id ->
            HeldSecret(
                id = id,
                name = VARIABLE_NAME,
                catalog = "listing check",
                secret = true,
                value = if (blank) "" else "sk-filled-in",
            )
        }
        val properties = ConnectionProperties()
        val router = ProxyRouter(ProxyRuleSource { emptyList() })
        val cipher = SecretCipher(TEST_KEY)
        return ModelProviderProbe(
            ConnectionProbe(properties, router, cipher),
            properties,
            ObjectMapper(),
            SecretReferences(variables, cipher),
            router,
            ModelClients(router),
        )
    }

    /**
     * Pointed at a variable and holding no copy of its own, which is what
     * choosing Reference on the card leaves behind.
     */
    private fun provider() = ModelProvider(
        workspaceId = 1,
        name = "OpenAI Ollama",
        type = ProviderType.OPENAI,
        endpoint = "https://example.invalid/v1",
        secret = null,
    ).apply { secretVariableId = VARIABLE_ID }

    private companion object {
        const val TEST_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
        const val VARIABLE_ID = 9L
        const val VARIABLE_NAME = "providerCredentialCheck_empty"
    }
}
