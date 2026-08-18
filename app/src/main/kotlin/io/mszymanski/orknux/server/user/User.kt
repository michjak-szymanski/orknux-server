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
