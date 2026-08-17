package io.mszymanski.orknux.server.attachment

import org.springframework.boot.context.properties.ConfigurationProperties

/** Where an attachment ends up. */
enum class AttachmentStorage {
    /**
     * A directory on the machine running this, one folder per workspace.
     *
     * The only one there is today. Object storage is the obvious second, which
     * is why what writes the bytes is behind an interface rather than a `File`
     * call in the endpoint.
     */
    FILESYSTEM,
}

/**
 * Whether files may be attached to a chat, and where they go.
 *
 * Configured here because it is a property of the installation rather than of
 * anybody using it: which disk has room, whether this deployment is allowed to
 * hold uploaded files at all. Administrators can turn it off from the screen —
 * that choice is stored and wins — but the location is the operator's, and a
 * setting somebody can change from a browser is not where a filesystem path
 * belongs.
 */
@ConfigurationProperties(prefix = "orknux.attachments")
data class AttachmentProperties(
    /**
     * What the installation allows. False here means no, whatever the screen
     * says: an operator who has turned this off has usually done so because the
     * disk is not theirs to fill.
     */
    val enabled: Boolean = true,
    val storage: AttachmentStorage = AttachmentStorage.FILESYSTEM,
    /**
     * The directory attachments are written under, with one folder per
     * workspace inside it.
     *
     * Relative to the working directory when it is relative, which is fine for
     * a development machine and wrong for a container — a deployment should
     * name an absolute path on a volume that outlives the process.
     */
    val location: String = "data/attachments",
    /** What one file may weigh; the upload refuses anything larger by name. */
    val maxFileSizeMb: Long = 25,
)
