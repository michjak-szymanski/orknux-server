package io.mszymanski.orknux.connector.proxy

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.slf4j.LoggerFactory
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.time.OffsetDateTime
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * One certificate authority this installation trusts, beyond the ones the JVM
 * came with.
 *
 * Installation-wide, and beside the proxy rules for the same reason they are
 * here: both are about what this server may reach on the way out, both are an
 * administrator's to decide, and neither belongs to a workspace.
 *
 * Nothing here is a secret. A certificate authority's certificate is what a
 * server hands every client that connects; the key that signs with it is the
 * secret, and it never comes near this. So it is stored in the clear and shown
 * in full — which is what lets somebody check *which* authority was added,
 * which is the question anybody debugging a refused handshake actually has.
 */
@Entity
@Table(name = "trusted_certificate")
class TrustedCertificate(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    /**
     * What an administrator calls it.
     *
     * A list of PEM blocks is a list nobody can read: they are base64 and they
     * all look alike. The name is how one is told from another when it comes
     * time to take one off.
     */
    @Column(nullable = false, length = 120)
    var name: String = "",

    /** The certificate itself, PEM. A chain is allowed; an internal one usually is one. */
    @Column(name = "pem", nullable = false, columnDefinition = "text")
    var pem: String = "",

    /** What the certificate says about itself, read once when it was added. */
    @Column(name = "subject", nullable = false, length = 500)
    var subject: String = "",

    @Column(name = "expires_at")
    var expiresAt: OffsetDateTime? = null,

    @Column(name = "added_at", nullable = false)
    var addedAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "added_by", nullable = false, length = 120)
    var addedBy: String = "",
)

interface TrustedCertificateRepository : JpaRepository<TrustedCertificate, Long>

/** What is on the list, as a screen shows it. */
data class TrustedCertificateView(
    val id: Long,
    val name: String,
    val pem: String,
    val subject: String,
    val expiresAt: OffsetDateTime?,
    val addedAt: OffsetDateTime,
    val addedBy: String,
    /** Whether it has already run out, so the row can say so rather than quietly not working. */
    val expired: Boolean,
)

class CertificateInvalidException(why: String) :
    RuntimeException("That is not a certificate this can read: $why")

class TrustedCertificateNotFoundException(id: Long) :
    RuntimeException("No trusted certificate with id $id")

/**
 * What outbound TLS should trust, asked of whatever knows.
 *
 * A functional interface for the reason [ProxyRuleSource] is one: it is the seam
 * a client is built against, so a test can hand over "the JVM's own" without
 * standing up a repository, and so nothing outbound has to know that the answer
 * comes from a table.
 */
fun interface OutboundTrust {

    /** The context to make connections with, or null to use the JVM's own. */
    fun context(): SSLContext?
}

/**
 * The certificate authorities this installation trusts, and the SSL context
 * built from them.
 *
 * **Added to what the JVM already trusts, never instead of it.** A server that
 * trusted only what was pasted would stop reaching anything with an ordinary
 * certificate the moment somebody added an internal authority — a change nobody
 * asked for, found weeks later by somebody else. Everything else about TLS is
 * left alone: the hostname is still checked, the chain still has to build, and
 * expiry is still enforced. What changes is the set of roots a chain may end at.
 *
 * There is no "trust everything" here and there should not be. That is the
 * setting people reach for at four in the afternoon and never take off again.
 *
 * The context is built once and rebuilt when the list changes, because building
 * one walks every root the JVM has and a client made per request would throw its
 * connection pool away as well. Null when the list is empty, which is every
 * installation that has not added one, and means "use the JVM's own".
 */
@Service
class TrustedCertificates(private val certificates: TrustedCertificateRepository) : OutboundTrust {

    private val log = LoggerFactory.getLogger(javaClass)

    private val held = AtomicReference<SSLContext?>(null)

    /** Set once the list has been read, so an empty list is not read again forever. */
    private val known = AtomicReference(false)

    /**
     * The context to make outbound TLS connections with, or null to use the
     * JVM's own.
     *
     * Null rather than a context built from only the default roots, because the
     * two are not the same thing to a caller: null lets an `HttpClient` be built
     * without touching TLS at all, which is what every installation that has
     * added nothing should get.
     */
    override fun context(): SSLContext? {
        if (!known.get()) rebuild()
        return held.get()
    }

    fun list(): List<TrustedCertificateView> =
        certificates.findAll().sortedBy { it.name.lowercase() }.map(::view)

    @Transactional
    fun add(name: String, pem: String, by: String): TrustedCertificateView {
        val wanted = name.trim()
        if (wanted.isEmpty()) throw CertificateInvalidException("it has no name")

        val text = pem.trim()
        val read = read(text)

        val saved = certificates.save(
            TrustedCertificate(
                name = wanted,
                pem = text,
                subject = read.subjectX500Principal.name.take(SUBJECT_LENGTH),
                expiresAt = read.notAfter.toInstant().atOffset(OffsetDateTime.now().offset),
                addedBy = by,
            ),
        )
        rebuild()
        log.info("Trusting certificate {} ({}), added by {}", saved.name, saved.subject, by)
        return view(saved)
    }

    @Transactional
    fun remove(id: Long): Boolean {
        val held = certificates.findByIdOrNull(id) ?: throw TrustedCertificateNotFoundException(id)
        certificates.delete(held)
        rebuild()
        log.info("No longer trusting certificate {} ({})", held.name, held.subject)
        return true
    }

    /**
     * The first certificate in a PEM block, so what was pasted can be described
     * and refused before it is stored.
     *
     * Refused here rather than at the first connection: a certificate that
     * cannot be parsed is a paste that went wrong, and finding that out on the
     * next handshake — as one more failure among the ones being debugged — is
     * the worst possible moment.
     */
    private fun read(pem: String): X509Certificate = try {
        val factory = java.security.cert.CertificateFactory.getInstance("X.509")
        val all = pem.byteInputStream(Charsets.UTF_8).use { factory.generateCertificates(it) }
        all.filterIsInstance<X509Certificate>().firstOrNull()
            ?: throw CertificateInvalidException("there is no certificate in it")
    } catch (failure: CertificateException) {
        throw CertificateInvalidException(failure.message ?: "it could not be parsed")
    }

    /**
     * The context, remade from the whole list.
     *
     * Remade rather than added to, because a trust manager cannot be changed
     * once it is built, and because the list is small enough that the difference
     * is not worth a second code path that only runs on removal.
     */
    private fun rebuild() {
        val all = certificates.findAll()
        known.set(true)
        if (all.isEmpty()) {
            held.set(null)
            return
        }

        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }

        // The JVM's own roots first, so this adds to them rather than replaces.
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(null as KeyStore?) }
            .trustManagers
            .filterIsInstance<X509TrustManager>()
            .forEach { manager ->
                manager.acceptedIssuers.forEachIndexed { at, issuer ->
                    store.setCertificateEntry("default-$at-${issuer.serialNumber}", issuer)
                }
            }

        val factory = java.security.cert.CertificateFactory.getInstance("X.509")
        all.forEach { row ->
            runCatching {
                // A PEM file may hold a chain, and an internal authority often is one.
                row.pem.byteInputStream(Charsets.UTF_8).use { factory.generateCertificates(it) }
                    .forEachIndexed { at, certificate -> store.setCertificateEntry("added-${row.id}-$at", certificate) }
            }.onFailure {
                // Stored and unreadable is a row somebody edited in the
                // database. Skipped rather than fatal: one bad row must not
                // take every other trusted authority down with it.
                log.warn("Trusted certificate {} could not be read and is being skipped", row.name, it)
            }
        }

        val managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(store) }
        held.set(SSLContext.getInstance("TLS").apply { init(null, managers.trustManagers, null) })
    }

    private fun view(row: TrustedCertificate) = TrustedCertificateView(
        id = requireNotNull(row.id),
        name = row.name,
        pem = row.pem,
        subject = row.subject,
        expiresAt = row.expiresAt,
        addedAt = row.addedAt,
        addedBy = row.addedBy,
        expired = row.expiresAt?.isBefore(OffsetDateTime.now()) == true,
    )

    private companion object {
        /** A distinguished name can run long; the column is what bounds it. */
        const val SUBJECT_LENGTH = 500
    }
}
