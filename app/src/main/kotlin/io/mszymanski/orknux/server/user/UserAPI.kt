package io.mszymanski.orknux.server.user

import io.mszymanski.orknux.server.security.RoleRepository
import org.springframework.security.crypto.password.PasswordEncoder
import io.mszymanski.orknux.server.security.WorkspaceAccess
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * The people this installation knows.
 *
 * Administrators only, like the roles beside it: the list is who exists and
 * what they may do, and that is not something to hand to everybody who can
 * sign in.
 *
 * Only INTERNAL users can be made or changed. An external user is the identity
 * provider's to define — recorded here at sign-in and read-only after, because
 * an edit here would hold exactly until they next arrive and then silently
 * lose to what the provider says.
 */
@Controller
class UserAPI(
    private val users: AppUserRepository,
    private val roles: RoleRepository,
    private val tokens: AppUserTokenRepository,
    private val internal: InternalAuthentication,
    private val encoder: PasswordEncoder,
    private val access: WorkspaceAccess,
) {

    @QueryMapping
    fun users(@Argument search: String?): List<UserView> {
        access.requireAdmin()
        val found = if (search.isNullOrBlank()) {
            users.findAll().sortedBy { it.displayName.lowercase() }
        } else {
            users.search(search.trim())
        }
        return found.map(::describe)
    }

    @QueryMapping
    fun user(@Argument id: Long): UserView? {
        access.requireAdmin()
        return users.findByIdOrNull(id)?.let(::describe)
    }

    @MutationMapping
    @Transactional
    fun createUser(@Argument input: UserInput): UserView {
        access.requireAdmin()
        val username = input.username?.trim().orEmpty()
        if (username.isEmpty()) throw UserNameInvalidException()
        users.findByUsername(username)?.let { throw UserNameTakenException(username) }

        val made = users.save(
            AppUser(
                username = username,
                displayName = input.displayName?.trim()?.takeIf { it.isNotEmpty() } ?: username,
                type = UserType.INTERNAL,
                roles = assigned(input.roleIds),
                lastModifiedBy = editor(),
            ),
        )
        return describe(made)
    }

    @MutationMapping
    @Transactional
    fun updateUser(@Argument id: Long, @Argument input: UserInput): UserView {
        access.requireAdmin()
        val held = users.findByIdOrNull(id) ?: throw UserNotFoundException(id)
        if (!held.editable) throw UserExternallyManagedException(held.username)

        input.displayName?.trim()?.takeIf { it.isNotEmpty() }?.let { held.displayName = it }
        input.roleIds?.let { held.roles = assigned(it) }
        held.lastModifiedAt = OffsetDateTime.now()
        held.lastModifiedBy = editor()
        return describe(users.save(held))
    }

    /**
     * An administrator giving somebody a password.
     *
     * Only for an internal user: an external one signs in through the provider,
     * and a password here would be a second one to forget. The old password is
     * not asked for - an administrator setting one is the answer to somebody
     * having lost theirs, and asking for what they lost would defeat it.
     */
    @MutationMapping
    @Transactional
    fun setUserPassword(@Argument id: Long, @Argument password: String): UserView {
        access.requireAdmin()
        val held = users.findByIdOrNull(id) ?: throw UserNotFoundException(id)
        if (held.type != UserType.INTERNAL) throw PasswordNotSettableException(held.username)
        if (password.length < SHORTEST_PASSWORD) throw PasswordTooShortException(SHORTEST_PASSWORD)

        held.passwordHash = encoder.encode(password)
        held.lastModifiedAt = OffsetDateTime.now()
        held.lastModifiedBy = editor()
        return describe(users.save(held))
    }

    /**
     * Somebody changing their own.
     *
     * The current one is asked for, because a session left open on a shared
     * screen should not be enough to lock its owner out of their own account.
     */
    @MutationMapping
    @Transactional
    fun changeMyPassword(@Argument currentPassword: String, @Argument newPassword: String): Boolean {
        val held = users.findByUsername(editor()) ?: throw UserNotFoundException(-1)
        if (held.type != UserType.INTERNAL) throw PasswordNotSettableException(held.username)
        if (newPassword.length < SHORTEST_PASSWORD) throw PasswordTooShortException(SHORTEST_PASSWORD)

        val hash = held.passwordHash
        if (hash == null || !encoder.matches(currentPassword, hash)) throw PasswordWrongException()

        held.passwordHash = encoder.encode(newPassword)
        held.lastModifiedAt = OffsetDateTime.now()
        held.lastModifiedBy = editor()
        users.save(held)
        return true
    }

    /**
     * An address, set by whoever it belongs to or by an administrator.
     *
     * One mutation for both, the way [createUserToken] beside it already does
     * it: an absent id means yourself, and a present one is somebody else and
     * needs the administrator role. Two mutations would be the same two rules
     * written twice, and the second one is where they would drift apart.
     *
     * Allowed for an external user, unlike everything else on this controller.
     * The rest of what the provider says about somebody is refused here because
     * the next sign-in would overwrite it - this is the one field that survives
     * it, which is the whole point of [AppUser.emailChosen].
     *
     * An empty address clears it and hands the field back to the provider,
     * rather than pinning an empty string that sign-in would then refuse to
     * fill.
     */
    @MutationMapping
    @Transactional
    fun setUserEmail(@Argument id: Long?, @Argument email: String?): UserView {
        val held = if (id == null) {
            users.findByUsername(editor()) ?: throw UserNotFoundException(-1)
        } else {
            val found = users.findByIdOrNull(id) ?: throw UserNotFoundException(id)
            if (!found.username.equals(editor(), ignoreCase = true)) access.requireAdmin()
            found
        }

        val wanted = email?.trim().orEmpty()
        if (wanted.isEmpty()) {
            held.email = null
            held.emailChosen = false
        } else {
            if (wanted.length > LONGEST_EMAIL || !EMAIL.matches(wanted)) throw EmailInvalidException(wanted)
            held.email = wanted
            held.emailChosen = true
        }
        held.lastModifiedAt = OffsetDateTime.now()
        held.lastModifiedBy = editor()
        return describe(users.save(held))
    }

    /** The tokens somebody has, which never includes the secrets themselves. */
    @QueryMapping
    fun myTokens(): List<TokenView> {
        val held = users.findByUsername(editor()) ?: return emptyList()
        return tokens.findByUserId(requireNotNull(held.id)).map(::describe)
    }

    @QueryMapping
    fun userTokens(@Argument id: Long): List<TokenView> {
        access.requireAdmin()
        return tokens.findByUserId(id).map(::describe)
    }

    /**
     * A new token, and the only time its secret is ever returned.
     *
     * Made for an internal user - an external one signs in with the provider
     * and a token here would outlive whatever the provider decides about them,
     * which is the one thing an installation must not let a token do.
     */
    @MutationMapping
    @Transactional
    fun createUserToken(@Argument id: Long?, @Argument name: String): NewTokenView {
        val held = if (id == null) {
            users.findByUsername(editor()) ?: throw UserNotFoundException(-1)
        } else {
            access.requireAdmin()
            users.findByIdOrNull(id) ?: throw UserNotFoundException(id)
        }
        if (held.type != UserType.INTERNAL) throw PasswordNotSettableException(held.username)

        val (stored, secret) = internal.mint(held, name.trim().ifEmpty { "Token" })
        return NewTokenView(describe(stored), secret)
    }

    @MutationMapping
    @Transactional
    fun deleteUserToken(@Argument id: Long): Boolean {
        val held = tokens.findByIdOrNull(id) ?: throw TokenNotFoundException(id)
        val owner = users.findByIdOrNull(held.userId)
        // Yours to remove, or an administrator's.
        if (owner?.username != editor()) access.requireAdmin()
        tokens.delete(held)
        return true
    }

    private fun describe(token: AppUserToken) = TokenView(
        id = requireNotNull(token.id),
        name = token.name,
        createdAt = token.createdAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        lastUsedAt = token.lastUsedAt?.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
    )

    /** Resolved now rather than trusted: a role id that is not a role is a typo, not a grant. */
    private fun assigned(roleIds: List<Long>?): MutableSet<io.mszymanski.orknux.server.security.Role> =
        roleIds.orEmpty().mapNotNull { roles.findByIdOrNull(it) }.toMutableSet()

    private fun editor(): String = SecurityContextHolder.getContext().authentication?.name ?: "system"

    private companion object {
        /**
         * Short enough not to be a fight, long enough to be worth having.
         *
         * A length and nothing else: composition rules push people towards
         * worse passwords they can remember rather than better ones they
         * cannot.
         */
        const val SHORTEST_PASSWORD = 12

        /** As long as an address is allowed to be, and the column that holds it. */
        const val LONGEST_EMAIL = 320

        /**
         * A name, an at sign, and a domain with a dot in it, and nothing else
         * asked. The elaborate patterns refuse addresses that work - a plus in
         * the name, a long suffix, a host somebody's employer invented - and
         * refusing somebody's real address is a worse failure than accepting an
         * odd-looking one. This catches the typo that matters: no at sign.
         */
        val EMAIL = Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")
    }

    private fun describe(user: AppUser) = UserView(
        id = requireNotNull(user.id),
        username = user.username,
        displayName = user.displayName,
        email = user.email,
        emailChosen = user.emailChosen,
        type = user.type,
        roles = user.roles.map { RoleRef(requireNotNull(it.id), it.name) }.sortedBy { it.name.lowercase() },
        editable = user.editable,
        hasPassword = user.hasPassword,
        lastModifiedAt = user.lastModifiedAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        lastModifiedBy = user.lastModifiedBy,
    )
}

data class UserView(
    val id: Long,
    val username: String,
    val displayName: String,
    /** Where to write to them, or null where nobody has said. */
    val email: String?,
    /** True where the address was typed rather than inherited from the provider. */
    val emailChosen: Boolean,
    val type: UserType,
    val roles: List<RoleRef>,
    /** False for anybody the identity provider defines. */
    val editable: Boolean,
    /** Whether they can sign in, which is not the same as existing. */
    val hasPassword: Boolean,
    val lastModifiedAt: String,
    val lastModifiedBy: String,
)

/** A role as a user's row names it: enough to show, enough to link. */
data class RoleRef(val id: Long, val name: String)

data class TokenView(
    val id: Long,
    val name: String,
    val createdAt: String,
    /** Null until something uses it. */
    val lastUsedAt: String?,
)

/** A token and its secret, which is returned exactly once. */
data class NewTokenView(val token: TokenView, val secret: String)

data class UserInput(
    /** Only read when creating; a username is who somebody is, not a field to edit. */
    val username: String?,
    val displayName: String?,
    val roleIds: List<Long>?,
)
