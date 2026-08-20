package io.mszymanski.orknux.server.issue

import io.mszymanski.orknux.connector.connection.MailDelivery
import io.mszymanski.orknux.connector.connection.MailMessage
import io.mszymanski.orknux.server.action.RecordingMailTransport
import io.mszymanski.orknux.server.user.AppUser
import io.mszymanski.orknux.server.user.AppUserRepository
import io.mszymanski.orknux.server.user.UserType
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser
import java.time.Duration

/**
 * The news desk's second delivery.
 *
 * Everything is real except the mail server: [RecordingMailTransport] stands in
 * for it, the same seam the password reset and the Send Email action are tested
 * through, so nothing here puts a message anywhere near a relay.
 *
 * What these pin down is that the mail follows the desk rather than deciding
 * anything of its own - the same audience, the same person dropped, one message
 * for one thing that happened - and that everything which stops a message being
 * sent is quiet rather than an error.
 */
@SpringBootTest(
    properties = [
        "orknux.mail.host=localhost",
        "orknux.mail.from=orknux@localhost",
        "orknux.web.base-url=https://orknux.example",
    ],
)
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class IssueNewsMailTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val issues: IssueRepository,
    @Autowired val news: IssueNewsRepository,
    @Autowired val marks: IssueNewsReadRepository,
    @Autowired val users: AppUserRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
    @Autowired val transport: RecordingMailTransport,
) {

    private var workspaceId: Long = 0
    private var claudeId: Long = 0
    private var danaId: Long = 0
    private var erinId: Long = 0

    @BeforeEach
    fun reset() {
        news.deleteAll()
        marks.deleteAll()
        issues.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        users.deleteAll()
        transport.forget()

        workspaceId = requireNotNull(workspaces.save(Workspace(name = "support")).id)
        // The person doing everything here. She has an address, which is what
        // makes "nobody is written to about their own doing" worth asserting.
        person("alice", "Alice", "alice@orknux.example")
        claudeId = person("claude", "Claude", "claude@orknux.example")
        danaId = person("dana", "Dana", "dana@orknux.example", wants = false)
        erinId = person("erin", "Erin", email = null)
    }

    @Test
    fun `a comment is posted to the assignee, and never to whoever wrote it`() {
        val id = fileForClaude()
        quiet()

        comment(id, "The customer has come back to us.")

        val message = mailed()
        assertThat(message.to).containsExactly("claude@orknux.example")
        assertThat(message.subject).isEqualTo("alice commented on #1: The reply is late")
        assertThat(message.body).contains("https://orknux.example/workspace/$workspaceId/issues/1")
        assertThat(message.body).contains("The customer has come back to us.")
        // The subject is what a lock screen shows without anybody choosing to
        // look, so what somebody wrote stays out of it.
        assertThat(message.subject).doesNotContain("customer")
        assertThat(everySent()).noneMatch { "alice@orknux.example" in it.to }
    }

    @Test
    fun `being handed an issue is posted, and says why it arrived`() {
        fileForClaude()

        val message = mailed()
        assertThat(message.to).containsExactly("claude@orknux.example")
        assertThat(message.subject).isEqualTo("alice assigned you #1: The reply is late")
        assertThat(message.body).contains("because it was assigned to you")
        assertThat(message.body).contains("Turn these off under Preferences: https://orknux.example/preferences")
    }

    @Test
    fun `somebody who has turned it off keeps the bell and loses the mail`() {
        val id = fileForClaude()
        quiet()
        observe(id, danaId)

        comment(id, "The customer has come back to us.")

        // Claude's is the one that proves the send happened at all, so Dana's
        // silence is a decision rather than a mail that had not arrived yet.
        mailed()
        assertThat(everySent()).noneMatch { "dana@orknux.example" in it.to }
        assertThat(news.latest(workspaceId, AssigneeKind.USER, "dana")).isNotEmpty()
    }

    @Test
    fun `somebody with no address is passed over quietly`() {
        val id = fileForClaude()
        quiet()
        observe(id, erinId)

        comment(id, "The customer has come back to us.")

        mailed()
        assertThat(everySent()).hasSize(1)
        assertThat(news.latest(workspaceId, AssigneeKind.USER, "erin")).isNotEmpty()
    }

    @Test
    fun `a comment naming somebody who is already watching is one message, not two`() {
        val id = fileForClaude()
        quiet()

        comment(id, "@Claude could you take a look?")

        val message = mailed()
        // Two rows, on purpose: the bell wants "commented" and "mentioned you"
        // as separate reasons to look. One mail, because they are one comment.
        assertThat(news.latest(workspaceId, AssigneeKind.USER, "claude").map { it.kind })
            .contains(IssueNewsKind.COMMENT, IssueNewsKind.MENTIONED)
        assertThat(everySent()).hasSize(1)
        assertThat(message.subject).isEqualTo("alice mentioned you on #1: The reply is late")
        assertThat(message.body).contains("because your name is in the comment")
    }

    @Test
    fun `a relay that refuses everything does not cost anybody their news`() {
        val id = fileForClaude()
        quiet()
        transport.answer = { _, _ -> MailDelivery.Refused("the server is not taking mail today", permanent = false) }

        comment(id, "The customer has come back to us.")

        // It was still handed over, and the bell still has it. The mail is a
        // courtesy on top of the record, never the record.
        await().atMost(WAIT).until { transport.sent.isNotEmpty() }
        assertThat(news.latest(workspaceId, AssigneeKind.USER, "claude").map { it.kind })
            .contains(IssueNewsKind.COMMENT)
    }

    private fun fileForClaude(title: String = "The reply is late"): Long =
        graphQlTester.document(
            """mutation { createIssue(input: {
                 workspaceId: $workspaceId, title: "$title",
                 assigneeKind: USER, assigneeId: "$claudeId"
               }) { id } }""",
        ).execute().path("createIssue.id").entity(Long::class.java).get()

    private fun comment(id: Long, said: String) {
        graphQlTester
            .document("""mutation { commentOnIssue(id: $id, content: "$said") { id } }""")
            .execute()
            .path("commentOnIssue.id")
            .hasValue()
    }

    private fun observe(id: Long, observerId: Long) {
        graphQlTester
            .document("""mutation { observeIssue(id: $id, observerKind: USER, observerId: "$observerId") { id } }""")
            .execute()
            .path("observeIssue.id")
            .hasValue()
    }

    /**
     * Waits for the message the setup itself caused, then forgets it.
     *
     * Filing an issue for somebody posts to them, and it is posted from another
     * thread - so clearing the recorder without waiting clears it before that
     * message lands, and it turns up in the middle of whatever is being asserted
     * on next. Every one of these begins with an issue somebody was given.
     */
    private fun quiet() {
        await().atMost(WAIT).until { transport.sent.isNotEmpty() }
        transport.forget()
    }

    /** The first message the relay was handed, waited for rather than assumed. */
    private fun mailed(): MailMessage =
        await().atMost(WAIT).until({ transport.sent.firstOrNull()?.second }) { it != null }!!

    /**
     * Copied before it is read: the mailer's thread is still running, and a list
     * being walked while it grows is not something to assert on.
     */
    private fun everySent(): List<MailMessage> = transport.sent.toList().map { it.second }

    private fun person(username: String, displayName: String, email: String?, wants: Boolean = true): Long =
        requireNotNull(
            users.save(
                AppUser(
                    username = username,
                    displayName = displayName,
                    type = UserType.INTERNAL,
                    email = email,
                    emailNotifications = wants,
                ),
            ).id,
        )

    /**
     * Stands in for the mail server. Primary rather than the only one, so the
     * real transport is still built by the context it replaces.
     */
    @TestConfiguration
    class FakeTransport {

        @Bean
        @Primary
        fun recordingMailTransport(): RecordingMailTransport = RecordingMailTransport()
    }

    private companion object {
        /** The mailer is a thread away, and nothing here waits on a real network. */
        val WAIT: Duration = Duration.ofSeconds(5)
    }
}

/**
 * The default installation: no relay configured, and nothing sent.
 *
 * Its own class because that is configuration rather than data - an installation
 * either has a mail server or it does not, and the whole point of this one is
 * that the tracker works identically without one.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class IssueNewsWithoutMailTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val issues: IssueRepository,
    @Autowired val news: IssueNewsRepository,
    @Autowired val marks: IssueNewsReadRepository,
    @Autowired val users: AppUserRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
    @Autowired val transport: RecordingMailTransport,
) {

    @Test
    fun `an installation with no mail server writes the news and sends nothing`() {
        news.deleteAll()
        marks.deleteAll()
        issues.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        users.deleteAll()
        transport.forget()

        val workspaceId = requireNotNull(workspaces.save(Workspace(name = "support")).id)
        val claudeId = requireNotNull(
            users.save(
                AppUser(
                    username = "claude",
                    displayName = "Claude",
                    type = UserType.INTERNAL,
                    email = "claude@orknux.example",
                ),
            ).id,
        )

        val id = graphQlTester.document(
            """mutation { createIssue(input: {
                 workspaceId: $workspaceId, title: "The reply is late",
                 assigneeKind: USER, assigneeId: "$claudeId"
               }) { id } }""",
        ).execute().path("createIssue.id").entity(Long::class.java).get()
        graphQlTester
            .document("""mutation { commentOnIssue(id: $id, content: "Anything at all.") { id } }""")
            .execute()
            .path("commentOnIssue.id")
            .hasValue()

        /*
         * Nothing reached the transport, and nothing was going to: the mailer
         * decides before it queues anything, so this is not a race being won.
         * The news is there, which is the half that has to keep working.
         */
        assertThat(transport.sent).isEmpty()
        assertThat(news.latest(workspaceId, AssigneeKind.USER, "claude").map { it.kind })
            .contains(IssueNewsKind.ASSIGNED, IssueNewsKind.COMMENT)
    }

    @TestConfiguration
    class FakeTransport {

        @Bean
        @Primary
        fun recordingMailTransport(): RecordingMailTransport = RecordingMailTransport()
    }
}
