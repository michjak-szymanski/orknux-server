package io.mszymanski.orknux.server.attachment

import io.mszymanski.orknux.server.chat.ChatProperties
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

/**
 * One thing about this installation that somebody changed from the screen.
 *
 * A key and a value, because these are few and unrelated: a table per setting
 * would be a migration every time an administrator is given a switch, and a
 * column per setting on a one-row table is the same thing with extra steps.
 *
 * What is here overrides the configuration file, with one exception — a file
 * that says no is final. An operator who turned attachments off did it because
 * the disk is not theirs to fill, and a browser should not be able to overrule
 * that.
 */
@Entity
@Table(name = "installation_setting")
class InstallationSetting(
    @Id
    @Column(name = "name", nullable = false, length = 120)
    val name: String = "",

    @Column(nullable = false, length = 500)
    var value: String = "",

    @Column(name = "last_modified_at", nullable = false)
    var lastModifiedAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "last_modified_by", nullable = false, length = 120)
    var lastModifiedBy: String = "",
)

interface InstallationSettingRepository : JpaRepository<InstallationSetting, String>

/** Where a name that is typed twice would be a bug. */
object SettingNames {
    const val ATTACHMENTS_ENABLED = "attachments.enabled"
    const val CHAT_ENABLED = "chat.enabled"
}

/**
 * What this installation allows, as the configuration file and the screen agree
 * it.
 *
 * The file is the floor and the screen is the switch: everything defaults to
 * what was configured, and an administrator may turn something off — or back on
 * where the file permitted it in the first place.
 */
@Service
class InstallationSettings(
    private val settings: InstallationSettingRepository,
    private val properties: AttachmentProperties,
    private val chat: ChatProperties,
) {

    /**
     * Whether this installation has a chat at all.
     *
     * Off is a real answer: an installation that exists to run workflows has no
     * use for a chat window, and one whose models are not cleared for
     * conversation should not be offering one. The same floor as attachments —
     * false in the file cannot be pressed back on.
     */
    fun chatEnabled(): Boolean {
        if (!chat.enabled) return false
        val held = settings.findByIdOrNull(SettingNames.CHAT_ENABLED) ?: return true
        return held.value.toBooleanStrictOrNull() ?: true
    }

    /** Whether the screen may offer the switch at all. */
    fun chatConfigurable(): Boolean = chat.enabled

    @Transactional
    fun setChatEnabled(enabled: Boolean, by: String) = hold(SettingNames.CHAT_ENABLED, enabled, by)

    /**
     * Whether a chat may carry files.
     *
     * False in the file means false here, whatever was last pressed: the
     * operator's answer is the one that holds when the two disagree, because
     * only one of them owns the disk.
     */
    fun attachmentsEnabled(): Boolean {
        if (!properties.enabled) return false
        val held = settings.findByIdOrNull(SettingNames.ATTACHMENTS_ENABLED) ?: return true
        return held.value.toBooleanStrictOrNull() ?: true
    }

    /** Whether the screen may offer the switch at all. */
    fun attachmentsConfigurable(): Boolean = properties.enabled

    fun storage(): AttachmentStorage = properties.storage

    fun location(): String = properties.location

    fun maxFileSizeMb(): Long = properties.maxFileSizeMb

    @Transactional
    fun setAttachmentsEnabled(enabled: Boolean, by: String) = hold(SettingNames.ATTACHMENTS_ENABLED, enabled, by)

    private fun hold(name: String, enabled: Boolean, by: String) {
        val held = settings.findByIdOrNull(name) ?: InstallationSetting(name = name)
        held.value = enabled.toString()
        held.lastModifiedAt = OffsetDateTime.now()
        held.lastModifiedBy = by
        settings.save(held)
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AttachmentProperties::class, ChatProperties::class)
class AttachmentConfig
