package io.mszymanski.orknux.connector.proxy

import io.mszymanski.orknux.connector.security.SECRET_COLUMN_LENGTH
import io.mszymanski.orknux.connector.security.SecretCipher
import io.mszymanski.orknux.connector.security.SecretConverter
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Component
import java.time.OffsetDateTime

/**
 * One installation-wide answer to "does this address need a proxy".
 *
 * Installation-wide rather than per workspace because the reason a proxy exists
 * is the network this process sits in, not the team whose workflow made the
 * call. Two workspaces running on the same host have the same egress, and a
 * per-workspace copy would mean an endpoint that has to be proxied works for
 * whoever remembered and fails for everybody else. It is also the only scope
 * that can cover the calls made for nobody in particular - the token grant a
 * model provider needs, the sweep that checks whether connections still answer.
 *
 * [pattern] is matched against the whole request URL, scheme and path included,
 * anywhere in it rather than end to end: a rule that says
 * `login\.microsoftonline\.com` is the one somebody will type, and anchoring is
 * still available to whoever wants it by writing `^` and `$`. Matching ignores
 * case, because the host half of a URL does.
 *
 * [position] decides which rule answers when more than one could; see
 * [ProxyRouter] for why that is a number an administrator sets rather than
 * something worked out from the patterns.
 */
@Entity
@Table(name = "proxy_rule")
class ProxyRule(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(nullable = false, length = 120)
    var name: String = "",

    @Column(nullable = false, length = 1000)
    var pattern: String = "",

    /**
     * Host and port rather than a proxy URL, because a URL invites the question
     * this cannot answer: the JDK's HTTP client speaks to a proxy over plain
     * HTTP and tunnels with CONNECT, so an `https://` in front of a proxy
     * address would be accepted on the screen and quietly ignored on the wire.
     */
    @Column(name = "proxy_host", nullable = false, length = 255)
    var proxyHost: String = "",

    @Column(name = "proxy_port", nullable = false)
    var proxyPort: Int = 0,

    @Column(length = 255)
    var username: String? = null,

    /** Encrypted in the database; see [SecretCipher]. Never returned by the API. */
    @Convert(converter = SecretConverter::class)
    @Column(length = SECRET_COLUMN_LENGTH)
    var password: String? = null,

    @Column(nullable = false)
    var enabled: Boolean = true,

    /** Ascending; the first enabled rule that matches is the one used. */
    @Column(nullable = false)
    var position: Int = 0,

    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "last_modified_at", nullable = false)
    var lastModifiedAt: OffsetDateTime = OffsetDateTime.now(),
)

interface ProxyRuleRepository : JpaRepository<ProxyRule, Long> {

    fun findByName(name: String): ProxyRule?

    /** Every rule in the order they are consulted, enabled or not. */
    fun findAllByOrderByPositionAscIdAsc(): List<ProxyRule>
}

/**
 * Where [ProxyRouter] gets the rules from.
 *
 * An interface with one method rather than the repository itself, so the part
 * that decides which proxy a URL goes through can be built and tested without a
 * database - and, more importantly, so nothing about routing a request depends
 * on JPA being initialised. The first outbound call this process makes can
 * happen while the context is still coming up.
 */
fun interface ProxyRuleSource {

    fun rules(): List<ProxyRule>
}

/** The stored rules, which is what the running application uses. */
@Component
class StoredProxyRules(private val repository: ProxyRuleRepository) : ProxyRuleSource {

    override fun rules(): List<ProxyRule> = repository.findAll(Sort.by("position", "id"))
}

class ProxyRuleNotFoundException(id: Long) : RuntimeException("No proxy rule with id $id")

class ProxyRuleNameTakenException(name: String) :
    RuntimeException("A proxy rule called $name already exists")

class ProxyRuleNameInvalidException : RuntimeException("A proxy rule name is required")

class ProxyRulePatternInvalidException(reason: String) :
    RuntimeException("That is not a usable regular expression: $reason")

class ProxyRuleProxyInvalidException(reason: String) : RuntimeException(reason)
