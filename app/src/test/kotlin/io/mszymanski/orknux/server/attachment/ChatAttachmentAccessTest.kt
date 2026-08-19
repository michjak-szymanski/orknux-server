package io.mszymanski.orknux.server.attachment

import io.mszymanski.orknux.server.chat.ChatSessionRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.test.context.support.WithMockUser

/**
 * Who may read the files sent into a chat.
 *
 * A chat belongs to the person who started it, and a document sent into one is
 * as private as the sentence it came with. Both were checked against the
 * workspace instead: everybody who could see the workspace could list the
 * attachments on anybody's conversation and download them, which is the whole
 * of somebody's private chat in file form.
 *
 * Bob is an administrator here, so that nothing in these turns on his not
 * seeing the workspace. He plainly can. What he cannot do is read a
 * conversation that is not his, and the files on it are part of it.
 *
 * Uploads and downloads go through the controller rather than over HTTP: what
 * is being checked is who is answered, not how multipart is parsed. The
 * location points at the build directory so a run leaves nothing in the working
 * tree.
 */
@SpringBootTest(properties = ["orknux.attachments.location=target/test-attachments"])
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class ChatAttachmentAccessTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val files: AttachmentAPI,
    @Autowired val attachments: ChatAttachmentRepository,
    @Autowired val chats: ChatSessionRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private var workspaceId: Long = 0

    @BeforeEach
    fun reset() {
        attachments.deleteAll()
        chats.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
    }

    @Test
    fun `the owner lists the files on their own chat, and downloads them`() {
        val chatId = startChat("Reviewing the outage")
        val uploaded = upload("postmortem.png")
        attach(chatId, uploaded)

        graphQlTester.document("""{ chatAttachments(chatId: $chatId) { filename uploadedBy } }""")
            .execute()
            .path("chatAttachments").entityList(Any::class.java).hasSize(1)
            .path("chatAttachments[0].filename").entity(String::class.java).isEqualTo("postmortem.png")
            .path("chatAttachments[0].uploadedBy").entity(String::class.java).isEqualTo("alice")

        val answer = files.download(uploaded)
        assertThat(answer.statusCode.value()).isEqualTo(200)
        assertThat(answer.body?.inputStream?.readBytes()).isEqualTo("what the graph looked like".toByteArray())
    }

    @Test
    fun `somebody else in the workspace neither lists nor downloads them`() {
        val chatId = startChat("Reviewing the outage")
        val uploaded = upload("postmortem.png")
        attach(chatId, uploaded)

        asUser("bob") {
            // The same nothing an id that was never a chat answers: a refusal
            // here would say that the chat is real and that alice is talking.
            graphQlTester.document("""{ chatAttachments(chatId: $chatId) { filename } }""")
                .execute().path("chatAttachments").entityList(Any::class.java).hasSize(0)

            assertThatThrownBy { files.download(uploaded) }
                .isInstanceOf(AttachmentNotFoundException::class.java)
        }

        // And alice still has it, so what was refused was bob rather than the file.
        assertThat(files.download(uploaded).statusCode.value()).isEqualTo(200)
    }

    @Test
    fun `a file still in the composer belongs to whoever uploaded it`() {
        val uploaded = upload("draft.png")

        asUser("bob") {
            assertThatThrownBy { files.download(uploaded) }
                .isInstanceOf(AttachmentNotFoundException::class.java)

            // Nor by pulling it into a chat of his own, which would otherwise
            // make it his to read by the rule that protects alice's.
            val his = startChat("Something else")
            graphQlTester.document(
                """mutation { attachToChat(chatId: $his, attachmentIds: [$uploaded]) { filename } }""",
            ).execute().path("attachToChat").entityList(Any::class.java).hasSize(0)

            assertThatThrownBy { files.download(uploaded) }
                .isInstanceOf(AttachmentNotFoundException::class.java)
        }

        assertThat(attachments.findAll().single().chatSessionId).isNull()
    }

    private fun startChat(title: String): Long = graphQlTester.document(
        """mutation { startChat(input: { workspaceId: $workspaceId, title: "$title" }) { id } }""",
    ).execute().path("startChat.id").entity(Long::class.java).get()

    /** Uploads one, and hands back the id of the row it made. */
    private fun upload(name: String): Long {
        val before = attachments.findAll().mapNotNull { it.id }.toSet()
        files.upload(
            workspaceId,
            listOf(MockMultipartFile("files", name, "image/png", "what the graph looked like".toByteArray())),
        )
        return requireNotNull(attachments.findAll().first { it.id !in before }.id)
    }

    private fun attach(chatId: Long, attachmentId: Long) = graphQlTester.document(
        """mutation { attachToChat(chatId: $chatId, attachmentIds: [$attachmentId]) { filename } }""",
    ).execute().path("attachToChat").entityList(Any::class.java).hasSize(1)

    /**
     * Half of what these check is that one person's chat is not another
     * person's, so who is asking has to be settable per call rather than per
     * test. The administrator authority comes with him: the point is that
     * seeing everything in the workspace is still not seeing the chat.
     */
    private fun <T> asUser(name: String, block: () -> T): T {
        val held = SecurityContextHolder.getContext().authentication
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(name, "n/a", listOf(SimpleGrantedAuthority("ROLE_ADMINS")))
        try {
            return block()
        } finally {
            SecurityContextHolder.getContext().authentication = held
        }
    }
}
