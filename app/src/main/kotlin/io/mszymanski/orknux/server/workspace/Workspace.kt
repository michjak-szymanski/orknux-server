package io.mszymanski.orknux.server.workspace

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
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

    /** Directory group whose members may see this workspace; null means administrators only. */
    @Column(name = "ldap_group", length = 255)
    var ldapGroup: String? = null,

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
)
