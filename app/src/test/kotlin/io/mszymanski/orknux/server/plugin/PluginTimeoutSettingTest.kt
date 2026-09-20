package io.mszymanski.orknux.server.plugin

import io.mszymanski.orknux.server.attachment.InstallationSettingRepository
import io.mszymanski.orknux.server.attachment.InstallationSettings
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser

/**
 * How long a plugin may take to load, as a setting rather than a restart.
 *
 * Which plugins an installation runs is not a decision made once at
 * deployment: a bundle that needs twelve seconds on a small machine is found
 * out by somebody watching it fail, not by whoever wrote the environment file.
 * So the file is where a fresh installation starts and the screen is the
 * answer from then on - the same bargain the retentions and the source cap
 * make.
 *
 * What this is *not* is the bound on a plugin's tool. That one belongs to the
 * workspace, because the wait is a person's and a model's; see
 * `ScriptTimeouts`. Keeping them apart is half the point of the setting having
 * its own name.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class PluginTimeoutSettingTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val settings: InstallationSettings,
    @Autowired val held: InstallationSettingRepository,
) {

    @BeforeEach
    fun clean() {
        held.deleteAll()
    }

    @Test
    fun `a fresh installation waits what the file says`() {
        graphQlTester.document("query { installationSettings { pluginTimeoutSeconds pluginTimeoutSecondsConfigured } }")
            .execute()
            .path("installationSettings.pluginTimeoutSeconds").entity(Int::class.java).isEqualTo(30)
            .path("installationSettings.pluginTimeoutSecondsConfigured").entity(Int::class.java).isEqualTo(30)

        assertThat(settings.pluginTimeoutMillis()).isEqualTo(30_000)
    }

    @Test
    fun `an administrator can change it, and the change is what is used`() {
        graphQlTester.document("mutation { setPluginTimeoutSeconds(seconds: 90) { pluginTimeoutSeconds } }")
            .execute()
            .path("setPluginTimeoutSeconds.pluginTimeoutSeconds").entity(Int::class.java).isEqualTo(90)

        // Read fresh rather than cached, so the next load is bounded by it -
        // no restart, which is the whole reason it is a setting.
        assertThat(settings.pluginTimeoutSeconds()).isEqualTo(90)
        assertThat(settings.pluginTimeoutMillis()).isEqualTo(90_000)

        // And what the file said is still there to go back to.
        graphQlTester.document("query { installationSettings { pluginTimeoutSecondsConfigured } }").execute()
            .path("installationSettings.pluginTimeoutSecondsConfigured").entity(Int::class.java).isEqualTo(30)
    }

    /**
     * A floor and a ceiling, refused by name. Zero would make every plugin
     * unloadable; an hour would hold a thread for an hour on a plugin that is
     * not slow but wrong.
     */
    @Test
    fun `a number outside the range is refused and nothing is stored`() {
        graphQlTester.document("mutation { setPluginTimeoutSeconds(seconds: 0) { pluginTimeoutSeconds } }")
            .execute().errors().satisfy { errors ->
                assertThat(errors).singleElement()
                    .satisfies({ assertThat(it.message).contains("not a number of seconds") })
            }

        graphQlTester.document("mutation { setPluginTimeoutSeconds(seconds: 3600) { pluginTimeoutSeconds } }")
            .execute().errors().satisfy { errors ->
                assertThat(errors).singleElement()
                    .satisfies({ assertThat(it.message).contains("not a number of seconds") })
            }

        assertThat(settings.pluginTimeoutSeconds()).isEqualTo(30)
    }
}
