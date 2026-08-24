package io.mszymanski.orknux.connector.security

import org.springframework.stereotype.Component

/**
 * One stored secret field's two ways of being answered: a copy of its own, or a
 * workspace variable secret it reads at the moment it is needed.
 *
 * **Per field, never per row.** This landed for a model provider, which has one
 * secret column - `secret` is the API key or the Entra client secret and
 * `authMethod` decides which - so a choice made once for the whole card looked
 * right. Nothing else is like that. A Slack connection keeps a bot token *and*
 * an app-level token, and "this connection uses a workspace secret" cannot mean
 * one of them without meaning the other. So the unit here is a field: one own
 * column, one reference column beside it, and every field asked separately.
 *
 * **By id.** The other way was by name, which is how an agent's grant to an MCP
 * server works, and it is what produced #170 and #228: a name is not an
 * identity, so renaming the variable or moving it to another catalog would leave
 * the holder pointing at nothing and go on displaying a reference it no longer
 * has. An id survives both, which leaves exactly one operation to guard -
 * deleting - and the application refuses that while something reads it.
 *
 * The rules are here rather than beside each field because they are the same
 * three every time, and the second field on a card is precisely where a second,
 * slightly different copy of them would be written.
 */
@Component
class SecretReferences(
    private val variables: SecretVariables,
    /** Only to recognise a credential that never came out of its envelope. */
    private val cipher: SecretCipher,
) {

    /**
     * The variable a save asked this field to be bound to, checked, or null for
     * a field keeping its own copy.
     *
     * Three refusals, all of them at the moment somebody can still fix it rather
     * than at the moment a call needs the credential: a caller that sent both
     * kinds at once, an id this workspace does not hold, and a variable that is
     * not one of the ones kept out of sight.
     */
    fun bind(workspaceId: Long, variableId: Long?, own: String?): Long? {
        if (variableId == null) return null
        if (own != null) throw SecretCredentialAmbiguousException()

        val held = variables.find(workspaceId, variableId) ?: throw SecretVariableNotFoundException(variableId)
        if (!held.secret) throw SecretVariableNotSecretException(held.name)
        return held.id
    }

    /**
     * The variable a field reads, for a screen that has to name it. Null when
     * the field keeps its own copy - and also when it reads one that has gone,
     * which is what a caller reports as a broken reference.
     */
    fun describe(workspaceId: Long, variableId: Long?): HeldSecret? =
        variableId?.let { variables.find(workspaceId, it) }

    /**
     * The credential itself, at the moment it is wanted.
     *
     * Five answers rather than a nullable string, because the four ways there is
     * nothing to send want four different things done about them and a caller
     * that cannot tell them apart sends whoever is debugging it to check the one
     * part of the configuration that is right. The wording is the caller's:
     * "there is no bot token" and "there is no API key" are the same fact about
     * different things, and this has no business writing either sentence.
     */
    fun read(workspaceId: Long, own: String?, variableId: Long?): HeldCredential {
        if (variableId != null) {
            val held = variables.find(workspaceId, variableId) ?: return HeldCredential.Missing(variableId)
            val value = held.value?.ifBlank { null } ?: return HeldCredential.Empty(held.name)
            if (cipher.isEncrypted(value)) return HeldCredential.Sealed(held.name)
            return HeldCredential.Held(value)
        }

        val kept = own?.ifBlank { null } ?: return HeldCredential.Absent
        /*
         * Stored, but not with the key this installation has now.
         *
         * Sending it as it stands would put the envelope on the wire and come
         * back a 401, which reads as a wrong credential rather than an
         * unreadable one - and those two want opposite things done about them.
         */
        if (cipher.isEncrypted(kept)) return HeldCredential.Sealed(null)
        return HeldCredential.Held(kept)
    }
}

/**
 * What reading one secret field came to.
 *
 * [Held] is the only one carrying a value, and it is not a data class: a
 * generated `toString` puts the credential into every log line that ever
 * interpolates it, including the ones written by frameworks nobody here owns.
 */
sealed interface HeldCredential {

    class Held(val value: String) : HeldCredential {

        override fun toString(): String = "HeldCredential.Held(…)"
    }

    /** No copy and no reference: nothing was ever configured here. */
    data object Absent : HeldCredential

    /** A reference pointing at nothing - a restore, or a hand-edited database. */
    data class Missing(val variableId: Long) : HeldCredential

    /** The variable is there and has never been given a value. */
    data class Empty(val name: String) : HeldCredential

    /**
     * Still in its envelope, because the key that sealed it is not the key this
     * installation has now.
     *
     * @property variable which variable, or null when it is the field's own copy.
     */
    data class Sealed(val variable: String?) : HeldCredential

    /** What to send, or null on any of the four ways there is nothing to send. */
    val credential: String? get() = (this as? Held)?.value
}

/**
 * A save that asked for both kinds of credential on one field at once.
 *
 * Refused rather than resolved by a precedence rule nobody could look up. The
 * two are a choice, and a caller sending both has not made it.
 */
class SecretCredentialAmbiguousException : RuntimeException(
    "A field keeps its own credential or reads one from a workspace variable, not both. " +
        "Send a value or a variable, not the two together.",
)

class SecretVariableNotFoundException(id: Long) :
    RuntimeException("No workspace variable with id $id in this workspace")

/**
 * A credential was bound to a variable anybody can read off the list.
 *
 * A VALUE is returned with the listing on purpose - hiding a channel name or a
 * threshold only makes them awkward to work with - so binding a credential to
 * one would put that credential on every member's screen. Only a SECRET may be
 * one, which is also why the application refuses to turn a bound one back into a
 * value.
 */
class SecretVariableNotSecretException(name: String) : RuntimeException(
    "\"$name\" is a workspace value rather than a secret, and a value is read with the list. " +
        "A credential has to be one of the ones kept out of sight.",
)
