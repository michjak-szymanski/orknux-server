package io.mszymanski.orknux.server.attachment

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Where the bytes of an attachment live.
 *
 * An interface for one implementation, deliberately: the second one — object
 * storage, most likely — should be a class beside this rather than an
 * `if` inside every place that touches a file. What is stored on the row is the
 * location this hands back, so nothing else needs to know the shape of it.
 */
interface AttachmentStore {

    /** @return where it went, in whatever form this storage understands. */
    fun put(workspaceId: Long, filename: String, bytes: ByteArray): String

    fun open(location: String): InputStream

    fun remove(location: String)
}

/**
 * Attachments on the disk of the machine running this, filed by workspace.
 *
 * One directory per workspace, which is the point: a workspace's files are its
 * own, and a mistake in a query should not be able to hand somebody another
 * workspace's document. The name on disk is not the name it was uploaded with —
 * that is on the row — because two people uploading `report.pdf` is normal, and
 * a filename from a browser is not something to trust with a path.
 */
@Component
class FilesystemAttachmentStore(private val settings: InstallationSettings) : AttachmentStore {

    override fun put(workspaceId: Long, filename: String, bytes: ByteArray): String {
        val folder = root().resolve(workspaceId.toString())
        Files.createDirectories(folder)

        val stored = "${UUID.randomUUID()}${extensionOf(filename)}"
        val target = folder.resolve(stored)
        bytes.inputStream().use { Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING) }

        log.debug("Attachment {} written for workspace {}", stored, workspaceId)
        // Relative to the configured root, so moving the root is a change to
        // one line of configuration rather than an update of every row.
        return "$workspaceId/$stored"
    }

    override fun open(location: String): InputStream = Files.newInputStream(resolve(location))

    override fun remove(location: String) {
        runCatching { Files.deleteIfExists(resolve(location)) }
            .onFailure { log.warn("Attachment {} could not be removed", location, it) }
    }

    /**
     * The file this location names, and only ever under the root.
     *
     * Checked rather than trusted: the location comes from a row today, but a
     * path that escapes its root is the sort of bug that is discovered from the
     * outside.
     */
    private fun resolve(location: String): Path {
        val root = root()
        val target = root.resolve(location).normalize()
        require(target.startsWith(root)) { "That attachment is not where it says it is" }
        return target
    }

    private fun root(): Path = Path.of(settings.location()).toAbsolutePath().normalize()

    /** Kept from the uploaded name, since it is how anything opening it decides what it is. */
    private fun extensionOf(filename: String): String {
        val dot = filename.lastIndexOf('.')
        if (dot <= 0 || dot == filename.length - 1) return ""

        val extension = filename.substring(dot)
        return if (extension.length <= MAX_EXTENSION && extension.drop(1).all(Char::isLetterOrDigit)) {
            extension.lowercase()
        } else {
            ""
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(FilesystemAttachmentStore::class.java)

        const val MAX_EXTENSION = 12
    }
}
