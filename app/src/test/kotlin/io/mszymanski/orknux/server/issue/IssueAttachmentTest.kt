package io.mszymanski.orknux.server.issue

import io.mszymanski.orknux.server.attachment.AttachmentProperties
import io.mszymanski.orknux.server.attachment.InstallationSettingRepository
import io.mszymanski.orknux.server.attachment.InstallationSettings
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.test.context.support.WithMockUser
import java.nio.file.Files
import java.nio.file.Path

/**
 * Files on an issue: uploaded, tied to what they belong to, taken off again.
 *
 * A bug report without the screenshot is half a bug report, so the tracker
 * carries files the same way the chat does - the installation's own storage,
 * its own size limit, its own switch. What is worth pinning down is the
 * ownership: whoever attached a file is the only person who may remove it, the
 * way whoever wrote a comment is the only person who may change it, and that is
 * not an administrator's to override.
 *
 * The other half is the tie. The bytes go up while the report is still being
 * written, so there is a moment where a file belongs to nothing, and getting
 * that wrong either loses the file or hands somebody another workspace's.
 *
 * Uploads are put through the controller rather than over HTTP: what is being
 * checked is which row is written and which file lands on disk, and a multipart
 * request would only add a layer between the test and the answer. The location
 * is pointed at the build directory so a run does not leave uploads in the
 * working tree.
 */
@SpringBootTest(properties = ["orknux.attachments.location=target/test-attachments"])
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class IssueAttachmentTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val uploads: IssueAttachmentAPI,
    @Autowired val attachments: IssueAttachmentRepository,
    @Autowired val issues: IssueRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
    @Autowired val settings: InstallationSettingRepository,
    @Autowired val installation: InstallationSettings,
    @Autowired val properties: AttachmentProperties,
) {

    private var workspaceId: Long = 0
    private var elsewhereId: Long = 0

    @BeforeEach
    fun reset() {
        attachments.deleteAll()
        issues.deleteAll()
        audit.deleteAll()
        settings.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "support")).id)
        elsewhereId = requireNotNull(workspaces.save(Workspace(name = "billing")).id)
    }

    /**
     * The switch back on, and this has to be *after* rather than only before.
     *
     * Attachments being on is a row in `installation_setting`, shared by every
     * test in the run and not rolled back by anything - so the last test in this
     * class, which turns them off on purpose, turned them off for whatever class
     * ran next. Cleaning up on the way in protects this class from its own
     * previous test and nothing else, which is what was here.
     *
     * What that cost: `TaskPictureTest` asks whether the drawing tool is offered,
     * and it is offered only where attachments are on. With them off the model is
     * never given the tool, asks for it anyway, is told there is no such tool, and
     * goes round the loop to its ceiling - eight calls to the model and no
     * drawing. It fails on the ordering alone, which is why it passed on one
     * machine and failed in CI.
     */
    @AfterEach
    fun putItBack() = settings.deleteAll()

    private fun file(title: String): Long =
        graphQlTester.document(
            """mutation { createIssue(input: { workspaceId: $workspaceId, title: "$title" }) { id } }""",
        ).execute().path("createIssue.id").entity(Long::class.java).get()

    /** Uploads one, and hands back the row it made. */
    private fun upload(
        name: String,
        type: String = "image/png",
        bytes: ByteArray = "a screenshot".toByteArray(),
        workspace: Long = workspaceId,
    ): IssueAttachment {
        val before = attachments.findAll().mapNotNull { it.id }.toSet()
        uploads.upload(workspace, listOf(MockMultipartFile("files", name, type, bytes)))
        return attachments.findAll().first { it.id !in before }
    }

    /** Where the bytes went, as the filesystem store filed them. */
    private fun onDisk(attachment: IssueAttachment): Path =
        Path.of(properties.location).toAbsolutePath().normalize().resolve(attachment.location)

    @Test
    fun `a file put on an issue is listed on it, with who attached it and how big it is`() {
        val id = file("The reply is late")
        val uploaded = upload("screenshot.png")
        assertThat(Files.exists(onDisk(uploaded))).isTrue()

        graphQlTester.document(
            """mutation { attachToIssue(id: $id, attachmentIds: [${uploaded.id}]) {
                 attachments { id filename contentType sizeBytes uploadedBy mine }
               } }""",
        ).execute()
            .path("attachToIssue.attachments").entityList(Any::class.java).hasSize(1)
            .path("attachToIssue.attachments[0].filename").entity(String::class.java).isEqualTo("screenshot.png")
            .path("attachToIssue.attachments[0].uploadedBy").entity(String::class.java).isEqualTo("alice")
            // A Float on the wire, since a file's size does not fit an Int.
            .path("attachToIssue.attachments[0].sizeBytes").entity(Double::class.java).isEqualTo(12.0)
            // Answered by the server, so the remove button and the refusal agree.
            .path("attachToIssue.attachments[0].mine").entity(Boolean::class.java).isEqualTo(true)

        // And it is still there when the issue is read again by its number.
        graphQlTester.document("""{ workspaceIssue(workspaceId: $workspaceId, number: 1) { attachments { filename } } }""")
            .execute()
            .path("workspaceIssue.attachments[0].filename").entity(String::class.java).isEqualTo("screenshot.png")

        assertThat(audit.findAll().map { it.message }).anySatisfy { assertThat(it).contains("attached screenshot.png") }
    }

    @Test
    fun `a file sent with a comment goes on the comment rather than on the issue`() {
        val id = file("The reply is late")
        val uploaded = upload("log.txt", type = "text/plain")

        graphQlTester.document(
            """mutation { commentOnIssue(id: $id, content: "Here is the log", attachmentIds: [${uploaded.id}]) {
                 attachments { id }
                 comments { content attachments { filename uploadedBy } }
               } }""",
        ).execute()
            // The issue itself has none: the file came with what was said.
            .path("commentOnIssue.attachments").entityList(Any::class.java).hasSize(0)
            .path("commentOnIssue.comments[0].attachments").entityList(Any::class.java).hasSize(1)
            .path("commentOnIssue.comments[0].attachments[0].filename").entity(String::class.java).isEqualTo("log.txt")
    }

    @Test
    fun `somebody else's file is not yours to remove`() {
        val id = file("The reply is late")
        // Bob's from the start, which is what a second person is - written
        // through the repository because the upload only ever writes as whoever
        // is signed in, and that is the whole point of this test.
        val his = attachments.save(
            IssueAttachment(
                workspaceId = workspaceId,
                issueId = id,
                filename = "his-screenshot.png",
                contentType = "image/png",
                sizeBytes = 12,
                location = "$workspaceId/his-screenshot.png",
                uploadedBy = "bob",
            ),
        )

        graphQlTester.document("""mutation { removeIssueAttachment(id: ${his.id}) }""")
            .execute()
            .errors().expect { it.message?.contains("whoever attached it") == true }
            .verify()

        // Alice is an administrator here, and it made no difference: the file is
        // still on the issue.
        graphQlTester.document("""{ workspaceIssue(workspaceId: $workspaceId, number: 1) { attachments { mine } } }""")
            .execute()
            .path("workspaceIssue.attachments").entityList(Any::class.java).hasSize(1)
            .path("workspaceIssue.attachments[0].mine").entity(Boolean::class.java).isEqualTo(false)
    }

    @Test
    fun `removing your own file takes the bytes with it`() {
        val id = file("The reply is late")
        val uploaded = upload("screenshot.png")
        graphQlTester.document("""mutation { attachToIssue(id: $id, attachmentIds: [${uploaded.id}]) { id } }""")
            .execute().path("attachToIssue.id").hasValue()

        graphQlTester.document("""mutation { removeIssueAttachment(id: ${uploaded.id}) }""")
            .execute()
            .path("removeIssueAttachment").entity(Boolean::class.java).isEqualTo(true)

        assertThat(attachments.findAll()).isEmpty()
        // A file nobody can reach any more is still a file on somebody's disk.
        assertThat(Files.exists(onDisk(uploaded))).isFalse()
    }

    @Test
    fun `a file uploaded in another workspace does not land on this issue`() {
        val id = file("The reply is late")
        val theirs = upload("their-screenshot.png", workspace = elsewhereId)

        graphQlTester.document(
            """mutation { attachToIssue(id: $id, attachmentIds: [${theirs.id}]) { attachments { filename } } }""",
        ).execute()
            // Dropped rather than argued with, the way a chat drops one: the
            // point is that it does not end up readable from here.
            .path("attachToIssue.attachments").entityList(Any::class.java).hasSize(0)

        assertThat(attachments.findAll().first { it.id == theirs.id }.issueId).isNull()
    }

    @Test
    fun `deleting an issue takes its files off the disk as well as out of the table`() {
        val id = file("The reply is late")
        val uploaded = upload("screenshot.png")
        graphQlTester.document("""mutation { attachToIssue(id: $id, attachmentIds: [${uploaded.id}]) { id } }""")
            .execute().path("attachToIssue.id").hasValue()

        graphQlTester.document("""mutation { deleteIssue(id: $id) }""")
            .execute().path("deleteIssue").entity(Boolean::class.java).isEqualTo(true)

        assertThat(attachments.findAll()).isEmpty()
        assertThat(Files.exists(onDisk(uploaded))).isFalse()
    }

    @Test
    fun `an installation with attachments turned off says so, rather than writing the file anyway`() {
        val id = file("The reply is late")
        val uploaded = upload("screenshot.png")
        installation.setAttachmentsEnabled(false, "alice")

        assertThatThrownBy { uploads.upload(workspaceId, listOf(MockMultipartFile("files", "another.png", "image/png", "x".toByteArray()))) }
            .hasMessageContaining("turned off for this installation")

        // And the half that writes the row refuses too: the switch can be
        // pressed between the upload and the issue being filed, and the file
        // that is already on disk is not a reason to accept it.
        graphQlTester.document("""mutation { attachToIssue(id: $id, attachmentIds: [${uploaded.id}]) { id } }""")
            .execute()
            .errors().expect { it.message?.contains("turned off for this installation") == true }
            .verify()
    }

    @Test
    fun `a file past the size this installation allows is refused by name`() {
        val tooBig = ByteArray((installation.maxFileSizeMb() * 1024 * 1024 + 1).toInt())

        assertThatThrownBy { uploads.upload(workspaceId, listOf(MockMultipartFile("files", "huge.png", "image/png", tooBig))) }
            .hasMessageContaining("huge.png")

        // Nothing was written: a refusal that leaves the bytes behind is not one.
        assertThat(attachments.findAll()).isEmpty()
    }
}
