package io.mszymanski.orknux.server.workspace

import io.mszymanski.orknux.server.security.Role
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.JoinTable
import jakarta.persistence.ManyToMany
import jakarta.persistence.Table

@Entity
@Table(name = "workspace")
class Workspace(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(nullable = false, unique = true)
    var name: String,

    @Column(length = 500)
    var description: String? = null,

    /**
     * The roles that open this workspace. Empty means administrators only.
     *
     * Roles rather than the name of a directory group: the group was the identity
     * provider's vocabulary in this application's model, it only ever made sense
     * for LDAP, and two workspaces meaning the same audience had no way to say so.
     *
     * Eagerly fetched because every access check needs them, and the set is small
     * by construction — a workspace has an audience, not a directory.
     */
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
        name = "workspace_role",
        joinColumns = [JoinColumn(name = "workspace_id")],
        inverseJoinColumns = [JoinColumn(name = "role_id")],
    )
    var roles: MutableSet<Role> = mutableSetOf(),

    /**
     * The roles that also *administer* this workspace. Empty means installation
     * administrators only, which is what every workspace had before this existed.
     *
     * Meant to be a subset of [roles]: administering a workspace one cannot see is
     * nothing, so `WorkspaceAPI.updateWorkspace` refuses a set that is not, and
     * `WorkspaceAccess.canSee` counts these too in case a database was edited by
     * hand.
     *
     * Per workspace, which is the whole of the idea — one role can lead the support
     * workspace and merely work in the backend one. Eagerly fetched for the same
     * reason [roles] is: the access check needs them on every call, and the set is
     * smaller still.
     */
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
        name = "workspace_admin_role",
        joinColumns = [JoinColumn(name = "workspace_id")],
        inverseJoinColumns = [JoinColumn(name = "role_id")],
    )
    var adminRoles: MutableSet<Role> = mutableSetOf(),

    /**
     * The model used for the small jobs nobody asks for — naming a chat from
     * what was said, first among them.
     *
     * Set for the workspace rather than per chat: it is not the conversation
     * anyone is having, and a cheap model is the right one for it even where
     * the chat uses an expensive one. Null means those jobs do not happen.
     */
    @Column(name = "companion_model_id")
    var companionModelId: Long? = null,

    /**
     * The model that turns speech into text, for the microphone in a chat.
     *
     * A workspace setting because it is about this installation's hardware —
     * where Whisper is running — rather than about any one conversation. Null
     * means the microphone is not offered: better than a button that fails.
     */
    @Column(name = "transcription_model_id")
    var transcriptionModelId: Long? = null,

    /**
     * The model that reads an answer aloud, for the speaker under one.
     *
     * The mirror of [transcriptionModelId], and a workspace setting for the same
     * reason. Null means no speaker is offered, which is better than one that
     * fails on every answer.
     */
    @Column(name = "speech_model_id")
    var speechModelId: Long? = null,

    /**
     * The model behind the quick chat, the panel that opens beside the page.
     *
     * Kept apart from the companion model because the jobs are not the same one:
     * naming a chat is a single cheap completion, while this answers questions
     * about the installation and calls orknux's own tools to do it. Null means
     * the button is not offered.
     */
    @Column(name = "quick_chat_model_id")
    var quickChatModelId: Long? = null,

    /**
     * Whether the quick chat may start things, or only look them up.
     *
     * Off by default, which is not the same as off by nature: the panel opens
     * over whatever somebody is reading, and a model that decides "run it" from
     * a question is a worse mistake there than on a page with a button on it.
     * A workspace that wants it can say so.
     */
    @Column(name = "quick_chat_may_write", nullable = false)
    var quickChatMayWrite: Boolean = false,
)
