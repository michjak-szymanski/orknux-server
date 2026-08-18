package io.mszymanski.orknux.server.user

import io.mszymanski.orknux.server.security.Role
import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.JoinTable
import jakarta.persistence.ManyToMany
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.OffsetDateTime

/** Where a user is true: at the identity provider, or here. */
enum class UserType {

    /** Made and managed in this application. An identity, not a login. */
    INTERNAL,

    /** Vouched for by LDAP or OIDC; recorded here when they sign in. */
    EXTERNAL,
}

/**
 * Somebody this installation knows.
 *
 * Until this existed a user was whatever the directory said at sign-in: a
 * username on a session and a name in the audit log, with nothing anywhere to
 * list, search, or assign. This is that list — the people things can be
 * assigned to and the names other screens resolve.
 *
 * What it is not is an account. Nothing here holds a credential and nothing
 * here signs anybody in; the front door still belongs to the provider. An
 * INTERNAL user is an identity this installation made up — someone to assign
 * an issue to, a name to show — and editing one changes what is shown, not
 * what anybody may do at sign-in.
 */
@Entity
@Table(name = "app_user")
class AppUser(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    /** How the provider — or the creator — spells them. Never changes case elsewhere. */
    @Column(nullable = false, length = 120)
    val username: String,

    @Column(name = "display_name", nullable = false, length = 200)
    var displayName: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    val type: UserType,

    /**
     * The roles an internal user is assigned.
     *
     * For an external user this records what the provider said at their last
     * sign-in — readable, so the list can answer "who can administer", and
     * never written from the edit screen, because the provider would overwrite
     * it the next time they arrive.
     */
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
        name = "app_user_role",
        joinColumns = [JoinColumn(name = "user_id")],
        inverseJoinColumns = [JoinColumn(name = "role_id")],
    )
    var roles: MutableSet<Role> = mutableSetOf(),

    /**
     * The hash of a password, for an internal user who has one.
     *
     * Null for everybody else, and for an internal user who is only ever
     * assigned things: an identity that cannot sign in is still a useful
     * identity. Never for an external user - their password belongs to the
     * directory that keeps it, and holding one here would make this a second
     * place to change it.
     */
    @Column(name = "password_hash", length = 100)
    var passwordHash: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "last_modified_at", nullable = false)
    var lastModifiedAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "last_modified_by", nullable = false, length = 120)
    var lastModifiedBy: String = "system",
) {

    /** Only what this installation made up is this installation's to change. */
    val editable: Boolean
        get() = type == UserType.INTERNAL

    /** Whether they can sign in at all, which is not the same as existing. */
    val hasPassword: Boolean
        get() = passwordHash != null
}

/**
 * A token: the same person by a different door.
 *
 * It carries a user and takes their roles, so what it may do is what they may
 * do - nothing here is a second permission system. Only the hash is kept: the
 * secret is shown once when it is made, and a table that could give it back
 * would be a password written down.
 */
@Entity
@Table(name = "app_user_token")
class AppUserToken(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "user_id", nullable = false)
    val userId: Long,

    /** What it is for, in the words of whoever made it. */
    @Column(nullable = false, length = 120)
    var name: String,

    @Column(name = "token_hash", nullable = false, length = 64)
    val tokenHash: String,

    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),

    /** When it was last accepted, so an unused one can be found and removed. */
    @Column(name = "last_used_at")
    var lastUsedAt: OffsetDateTime? = null,
)

interface AppUserTokenRepository : JpaRepository<AppUserToken, Long> {

    fun findByTokenHash(tokenHash: String): AppUserToken?

    fun findByUserId(userId: Long): List<AppUserToken>
}

interface AppUserRepository : JpaRepository<AppUser, Long> {

    /** However the name was typed: one person per name is the table's own rule. */
    @Query("select u from AppUser u where lower(u.username) = lower(:username)")
    fun findByUsername(username: String): AppUser?

    /** The list, filtered the way the search box asks: by name, either of them. */
    @Query(
        "select u from AppUser u where lower(u.username) like lower(concat('%', :search, '%')) " +
            "or lower(u.displayName) like lower(concat('%', :search, '%')) order by lower(u.displayName)",
    )
    fun search(search: String): List<AppUser>
}

class UserNotFoundException(id: Long) : RuntimeException("No user with id $id")

class UserNameTakenException(username: String) :
    RuntimeException("A user named \"$username\" already exists")

class UserNameInvalidException : RuntimeException("A user needs a username")

class PasswordTooShortException(shortest: Int) :
    RuntimeException("A password needs at least $shortest characters")

class PasswordWrongException : RuntimeException("That is not the current password")

/**
 * Somebody tried to give a password to a user the directory owns.
 *
 * Not a permission that can be granted: the provider is where they are true,
 * and a password here would be a second one to forget.
 */
class PasswordNotSettableException(username: String) : RuntimeException(
    "\"$username\" signs in through the identity provider, so there is no password to set here",
)

class TokenNotFoundException(id: Long) : RuntimeException("No token with id $id")

/**
 * Somebody tried to edit an external user.
 *
 * Says why rather than only refusing: the provider is where an external user is
 * true, and an edit here would hold until their next sign-in and then silently
 * lose.
 */
class UserExternallyManagedException(username: String) : RuntimeException(
    "\"$username\" comes from the identity provider and cannot be edited here. " +
        "What the provider says about them overwrites this at their next sign-in.",
)
