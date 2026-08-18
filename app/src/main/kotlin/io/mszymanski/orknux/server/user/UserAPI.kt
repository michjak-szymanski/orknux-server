package io.mszymanski.orknux.server.user

import io.mszymanski.orknux.server.security.RoleRepository
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

    /** Resolved now rather than trusted: a role id that is not a role is a typo, not a grant. */
    private fun assigned(roleIds: List<Long>?): MutableSet<io.mszymanski.orknux.server.security.Role> =
        roleIds.orEmpty().mapNotNull { roles.findByIdOrNull(it) }.toMutableSet()

    private fun editor(): String = SecurityContextHolder.getContext().authentication?.name ?: "system"

    private fun describe(user: AppUser) = UserView(
        id = requireNotNull(user.id),
        username = user.username,
        displayName = user.displayName,
        type = user.type,
        roles = user.roles.map { RoleRef(requireNotNull(it.id), it.name) }.sortedBy { it.name.lowercase() },
        editable = user.editable,
        lastModifiedAt = user.lastModifiedAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        lastModifiedBy = user.lastModifiedBy,
    )
}

data class UserView(
    val id: Long,
    val username: String,
    val displayName: String,
    val type: UserType,
    val roles: List<RoleRef>,
    /** False for anybody the identity provider defines. */
    val editable: Boolean,
    val lastModifiedAt: String,
    val lastModifiedBy: String,
)

/** A role as a user's row names it: enough to show, enough to link. */
data class RoleRef(val id: Long, val name: String)

data class UserInput(
    /** Only read when creating; a username is who somebody is, not a field to edit. */
    val username: String?,
    val displayName: String?,
    val roleIds: List<Long>?,
)
