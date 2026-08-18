package io.mszymanski.orknux.server.user

import io.mszymanski.orknux.server.security.Role
import io.mszymanski.orknux.server.security.RoleRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.authentication.event.AuthenticationSuccessEvent
import org.springframework.security.test.context.support.WithMockUser

/**
 * The people this installation knows: listed, made, edited - and the line
 * between the two kinds.
 *
 * An INTERNAL user is this installation's own and changes here; an EXTERNAL
 * one is recorded at sign-in and refused edits, because the provider would
 * overwrite them at the next arrival anyway.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class UserAPITest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val users: AppUserRepository,
    @Autowired val roles: RoleRepository,
    @Autowired val detection: UserDetection,
    @Autowired val internal: InternalAuthentication,
) {

    @BeforeEach
    fun reset() {
        users.deleteAll()
        roles.findAll().filter { !it.builtin }.forEach(roles::delete)
    }

    @Test
    fun `an internal user is made with roles and can be renamed`() {
        val role = roles.save(Role(name = "reviewers"))

        val made = graphQlTester.document(
            """mutation { createUser(input: {
                 username: "helpdesk", displayName: "The Helpdesk", roleIds: [${role.id}]
               }) { id username displayName type editable roles { name } } }""",
        ).execute()
            .path("createUser.type").entity(String::class.java).isEqualTo("INTERNAL")
            .path("createUser.editable").entity(Boolean::class.java).isEqualTo(true)
            .path("createUser.roles[0].name").entity(String::class.java).isEqualTo("reviewers")
            .path("createUser.id").entity(Long::class.java).get()

        graphQlTester.document(
            """mutation { updateUser(id: $made, input: { displayName: "Front Desk", roleIds: [] })
               { displayName roles { name } } }""",
        ).execute()
            .path("updateUser.displayName").entity(String::class.java).isEqualTo("Front Desk")
            .path("updateUser.roles").entityList(Any::class.java).hasSize(0)
    }

    @Test
    fun `signing in writes somebody down, once`() {
        val arrival = AuthenticationSuccessEvent(TestingAuthenticationToken("bob", "n/a", "ROLE_USERS"))
        detection.onSignIn(arrival)
        detection.onSignIn(arrival)

        val held = users.findByUsername("bob")
        assertThat(held?.type).isEqualTo(UserType.EXTERNAL)
        assertThat(users.findAll().count { it.username == "bob" }).isEqualTo(1)
    }

    @Test
    fun `a password lets an internal user sign in, and a token stands in for them`() {
        val id = graphQlTester.document(
            """mutation { createUser(input: { username: "claude", displayName: "Claude" }) { id hasPassword } }""",
        ).execute()
            // Made without one: an identity that cannot sign in is still useful.
            .path("createUser.hasPassword").entity(Boolean::class.java).isEqualTo(false)
            .path("createUser.id").entity(Long::class.java).get()

        graphQlTester.document(
            """mutation { setUserPassword(id: $id, password: "correct horse battery") { hasPassword } }""",
        ).execute()
            .path("setUserPassword.hasPassword").entity(Boolean::class.java).isEqualTo(true)

        assertThat(internal.authenticate("claude", "correct horse battery")).isNotNull()
        assertThat(internal.authenticate("claude", "not it")).isNull()
        // However it was typed, they are the same person - but the password is not.
        assertThat(internal.authenticate("CLAUDE", "correct horse battery")).isNotNull()

        val secret = graphQlTester.document(
            """mutation { createUserToken(id: $id, name: "Claude Code") { secret token { name lastUsedAt } } }""",
        ).execute()
            .path("createUserToken.token.lastUsedAt").valueIsNull()
            .path("createUserToken.secret").entity(String::class.java).get()

        // Says what it is, so it can be recognised in a log and revoked.
        assertThat(secret).startsWith("orkx_")

        val carried = internal.authenticateToken(secret)
        assertThat(carried?.name).isEqualTo("claude")
        assertThat(internal.authenticateToken("orkx_nonsense")).isNull()
    }

    @Test
    fun `a short password is refused, and an external user has none to set`() {
        val id = graphQlTester.document(
            """mutation { createUser(input: { username: "helpdesk" }) { id } }""",
        ).execute().path("createUser.id").entity(Long::class.java).get()

        graphQlTester.document("""mutation { setUserPassword(id: $id, password: "short") { id } }""")
            .execute()
            .errors().expect { it.message?.contains("at least 12") == true }
            .verify()

        detection.onSignIn(AuthenticationSuccessEvent(TestingAuthenticationToken("frank", "n/a", "ROLE_USERS")))
        val external = requireNotNull(users.findByUsername("frank"))
        graphQlTester.document(
            """mutation { setUserPassword(id: ${external.id}, password: "correct horse battery") { id } }""",
        ).execute()
            .errors().expect { it.message?.contains("identity provider") == true }
            .verify()
    }

    @Test
    fun `an external user is not this installation's to edit`() {
        detection.onSignIn(AuthenticationSuccessEvent(TestingAuthenticationToken("carol", "n/a", "ROLE_USERS")))
        val held = requireNotNull(users.findByUsername("carol"))

        graphQlTester.document(
            """mutation { updateUser(id: ${held.id}, input: { displayName: "Someone Else" }) { id } }""",
        ).execute()
            .errors().expect { it.message?.contains("identity provider") == true }
            .verify()
    }

    @Test
    fun `one person per name, whichever kind`() {
        detection.onSignIn(AuthenticationSuccessEvent(TestingAuthenticationToken("dave", "n/a", "ROLE_USERS")))

        graphQlTester.document(
            """mutation { createUser(input: { username: "DAVE" }) { id } }""",
        ).execute()
            .errors().expect { it.message?.contains("already exists") == true }
            .verify()
    }

    @Test
    fun `the search reads both names`() {
        graphQlTester.document("""mutation { createUser(input: { username: "helpdesk", displayName: "Front Desk" }) { id } }""")
            .execute().path("createUser.id").entity(Long::class.java).get()
        detection.onSignIn(AuthenticationSuccessEvent(TestingAuthenticationToken("erin", "n/a", "ROLE_USERS")))

        graphQlTester.document("""{ users(search: "front") { username } }""")
            .execute()
            .path("users").entityList(Any::class.java).hasSize(1)
            .path("users[0].username").entity(String::class.java).isEqualTo("helpdesk")
    }
}
