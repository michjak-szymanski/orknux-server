package io.mszymanski.orknux.server.integration

import io.mszymanski.orknux.connector.proxy.TrustedCertificateView
import io.mszymanski.orknux.connector.proxy.TrustedCertificates
import io.mszymanski.orknux.server.security.WorkspaceAccess
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Controller

/**
 * The certificate authorities this installation trusts, for the Networking page.
 *
 * The same division [ProxyRuleAPI] uses, and it sits beside it for the same
 * reason: the connection module holds the list and knows nothing about who is
 * asking, and this decides that. Administrators only, because trusting an
 * authority changes what every outbound connection in the installation will
 * accept — it is not a workspace's decision to make on everybody else's behalf.
 *
 * Nothing here is a secret and nothing is masked. A certificate authority's
 * certificate is what a server hands every client that connects; being able to
 * read back which one was added is the whole point of the screen.
 */
@Controller
class TrustedCertificateAPI(
    private val certificates: TrustedCertificates,
    private val access: WorkspaceAccess,
) {

    @QueryMapping
    fun trustedCertificates(): List<TrustedCertificateView> {
        access.requireAdmin()
        return certificates.list()
    }

    /**
     * Adds one, refusing anything that is not a certificate.
     *
     * Refused here rather than at the first handshake. A paste that went wrong
     * is the ordinary way this fails, and finding out on the next connection —
     * as one more failure among the ones being debugged — is the worst possible
     * moment to be told.
     */
    @MutationMapping
    fun trustCertificate(@Argument name: String, @Argument pem: String): TrustedCertificateView {
        access.requireAdmin()
        return certificates.add(name, pem, currentUser())
    }

    /**
     * Stops trusting one.
     *
     * Takes effect on the next connection rather than at the next restart, which
     * is the half that would be easy to get wrong: an administrator who removes
     * an authority has decided something, and a client cached for the life of
     * the process would go on accepting it until somebody noticed.
     */
    @MutationMapping
    fun untrustCertificate(@Argument id: Long): Boolean {
        access.requireAdmin()
        return certificates.remove(id)
    }

    private fun currentUser(): String =
        SecurityContextHolder.getContext().authentication?.name ?: "somebody"
}
