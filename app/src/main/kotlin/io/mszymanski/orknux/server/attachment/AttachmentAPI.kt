package io.mszymanski.orknux.server.attachment

import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import io.mszymanski.orknux.server.workspace.WorkspaceNotFoundException
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.springframework.core.io.InputStreamResource
import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

/**
 * Uploading files to a chat, and reading them back.
 *
 * REST for the same reason transcription is: what crosses here is bytes, and a
 * multipart form is what a browser produces from a file picker.
 *
 * Every file is filed under the workspace that uploaded it, and reading one is
 * checked against that workspace rather than against who uploaded it — a
 * document attached to a shared chat belongs to the people who share it.
 */
@RestController
class AttachmentAPI(
    private val attachments: ChatAttachmentRepository,
    private val workspaces: WorkspaceRepository,
    private val store: AttachmentStore,
    private val settings: InstallationSettings,
    private val access: WorkspaceAccess,
    private val auditRecorder: WorkspaceAuditRecorder,
    private val downloads: AttachmentDownloads,
) {

    /** Several at once, because a file picker hands over several at once. */
    @PostMapping("/api/workspaces/{workspaceId}/attachments")
    @Transactional
    fun upload(
        @PathVariable workspaceId: Long,
        @RequestParam("files") files: List<MultipartFile>,
    ): ResponseEntity<Any> {
        val workspace = workspaces.findByIdOrNull(workspaceId) ?: throw WorkspaceNotFoundException(workspaceId)
        access.requireVisible(workspace)
        if (!settings.attachmentsEnabled()) throw AttachmentsDisabledException()

        val limit = settings.maxFileSizeMb() * 1024 * 1024
        val kept = files.filterNot { it.isEmpty }.map { file ->
            val name = file.originalFilename?.trim()?.ifEmpty { null } ?: "file"
            if (file.size > limit) throw AttachmentTooLargeException(name, settings.maxFileSizeMb())

            val location = store.put(workspaceId, name, file.bytes)
            attachments.save(
                ChatAttachment(
                    workspaceId = workspaceId,
                    filename = name.takeLast(MAX_NAME),
                    contentType = file.contentType?.ifBlank { null } ?: "application/octet-stream",
                    sizeBytes = file.size,
                    location = location,
                    uploadedBy = currentUser(),
                ),
            )
        }

        if (kept.isNotEmpty()) {
            val what = if (kept.size == 1) kept.first().filename else "${kept.size} files"
            auditRecorder.record(workspaceId, WorkspaceAuditCategory.CHAT, "Attached $what to a chat")
        }
        return ResponseEntity.ok(mapOf("attachments" to kept.map(::describe)))
    }

    /**
     * Hands the file back.
     *
     * What may be shown rather than downloaded, and the headers that go with
     * it, are [AttachmentDownloads]' business: an issue's files are served the
     * same way, and one list of what is safe to render is the only number of
     * lists that stays right.
     */
    @GetMapping("/api/attachments/{id}")
    fun download(@PathVariable id: Long): ResponseEntity<InputStreamResource> {
        val attachment = attachments.findByIdOrNull(id) ?: throw AttachmentNotFoundException(id)
        val workspace = workspaces.findByIdOrNull(attachment.workspaceId)
            ?: throw WorkspaceNotFoundException(attachment.workspaceId)
        access.requireVisible(workspace)

        return downloads.serve(
            filename = attachment.filename,
            contentType = attachment.contentType,
            sizeBytes = attachment.sizeBytes,
            location = attachment.location,
        )
    }

    private fun describe(attachment: ChatAttachment) = mapOf(
        "id" to requireNotNull(attachment.id).toString(),
        "filename" to attachment.filename,
        "contentType" to attachment.contentType,
        "sizeBytes" to attachment.sizeBytes,
    )

    private fun currentUser(): String =
        SecurityContextHolder.getContext().authentication?.name ?: "system"

    private companion object {
        /** Long enough for a real name, short enough for the column. */
        const val MAX_NAME = 255
    }
}

/**
 * Tying uploaded files to the chat they were sent with.
 *
 * Separate from the upload because the two happen at different moments: a file
 * goes up while the sentence is still being typed, and which chat it belongs to
 * is only settled when the message is sent — a first message makes the chat.
 */
@org.springframework.stereotype.Controller
class ChatAttachmentAPI(
    private val attachments: ChatAttachmentRepository,
    private val chats: io.mszymanski.orknux.server.chat.ChatSessionRepository,
    private val workspaces: WorkspaceRepository,
    private val access: WorkspaceAccess,
) {

    /** What was attached to one chat, oldest first. */
    @org.springframework.graphql.data.method.annotation.QueryMapping
    fun chatAttachments(
        @org.springframework.graphql.data.method.annotation.Argument chatId: Long,
    ): List<ChatAttachmentView> {
        val chat = chats.findByIdOrNull(chatId) ?: return emptyList()
        val workspace = workspaces.findByIdOrNull(chat.workspaceId)
            ?: throw WorkspaceNotFoundException(chat.workspaceId)
        access.requireVisible(workspace)

        return attachments.findByChatSessionIdOrderByUploadedAtAsc(chatId).map(::describeView)
    }

    /**
     * Says which chat these belong to.
     *
     * Only files of that chat's own workspace, and only ones not already spoken
     * for: an id from somewhere else is ignored rather than argued with, since
     * the message it came with has already been sent.
     */
    @org.springframework.graphql.data.method.annotation.MutationMapping
    @Transactional
    fun attachToChat(
        @org.springframework.graphql.data.method.annotation.Argument chatId: Long,
        @org.springframework.graphql.data.method.annotation.Argument attachmentIds: List<Long>,
    ): List<ChatAttachmentView> {
        val chat = chats.findByIdOrNull(chatId) ?: throw AttachmentNotFoundException(chatId)
        val workspace = workspaces.findByIdOrNull(chat.workspaceId)
            ?: throw WorkspaceNotFoundException(chat.workspaceId)
        access.requireVisible(workspace)

        val kept = attachmentIds
            .mapNotNull { attachments.findByIdOrNull(it) }
            .filter { it.workspaceId == chat.workspaceId && it.chatSessionId == null }
            .onEach { it.chatSessionId = chatId }
        return kept.map(::describeView)
    }

    private fun describeView(attachment: ChatAttachment) = ChatAttachmentView(
        id = requireNotNull(attachment.id),
        filename = attachment.filename,
        contentType = attachment.contentType,
        sizeBytes = attachment.sizeBytes,
        uploadedBy = attachment.uploadedBy,
    )
}

data class ChatAttachmentView(
    val id: Long,
    val filename: String,
    val contentType: String,
    val sizeBytes: Long,
    val uploadedBy: String,
)
