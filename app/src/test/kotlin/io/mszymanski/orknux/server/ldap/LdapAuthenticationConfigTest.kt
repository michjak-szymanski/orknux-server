package io.mszymanski.orknux.server.ldap

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.GrantedAuthority

@SpringBootTest
class LdapAuthenticationConfigTest(
    @Autowired val authenticationManager: AuthenticationManager,
) {

    @Test
    fun `authenticates a directory user and maps their groups to authorities`() {
        val authentication = authenticationManager.authenticate(
            UsernamePasswordAuthenticationToken("alice", "password"),
        )

        assertThat(authentication.isAuthenticated).isTrue()
        assertThat(authentication.name).isEqualTo("alice")
        assertThat(authentication.roles()).containsExactlyInAnyOrder("ROLE_ADMINS", "ROLE_USERS")
    }

    @Test
    fun `authenticates a user who belongs to a single group`() {
        val authentication = authenticationManager.authenticate(
            UsernamePasswordAuthenticationToken("bob", "password"),
        )

        // bob is also in cn=backend, which is what grants him the backend workspace.
        assertThat(authentication.roles()).containsExactlyInAnyOrder("ROLE_USERS", "ROLE_BACKEND")
    }

    @Test
    fun `rejects a wrong password`() {
        assertThatThrownBy {
            authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken("alice", "wrong"),
            )
        }.isInstanceOf(BadCredentialsException::class.java)
    }

    @Test
    fun `rejects an unknown user`() {
        assertThatThrownBy {
            authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken("nobody", "password"),
            )
        }.isInstanceOf(BadCredentialsException::class.java)
    }

    /**
     * Spring Security 7 also grants a `FACTOR_*` authority recording how the user authenticated,
     * so assert on the group-derived roles only.
     */
    private fun Authentication.roles(): List<String> =
        authorities.mapNotNull(GrantedAuthority::getAuthority).filter { it.startsWith("ROLE_") }
}
