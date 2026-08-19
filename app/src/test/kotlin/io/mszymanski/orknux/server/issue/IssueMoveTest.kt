package io.mszymanski.orknux.server.issue

import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.agent.AgentType
import io.mszymanski.orknux.server.attachment.AttachmentProperties
import io.mszymanski.orknux.server.attachment.InstallationSettingRepository
import io.mszymanski.orknux.server.user.AppUser
import io.mszymanski.orknux.server.user.AppUserRepository
import io.mszymanski.orknux.server.user.UserType
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.test.context.support.WithMockUser
import java.nio.file.Files
import java.nio.file.Path

/**
 * An issue filed in the wrong workspace, put in the right one.
 *
 * The button is the easy half. What these pin down is everything an issue's
 * identity is tangled with: its number, which is per workspace and so cannot
 * come with it; its files, which are stored per workspace on the disk and have
 * to; and the things hanging off it that may have no counterpart where it is
 * going, which are what the move refuses over rather than quietly tidying away.
 *
 * The attachment location points at the build directory so that a run does not
 * leave files in the working tree, exactly as the attachment tests do.
 */
@SpringBootTest(properties = ["orknux.attachments.location=target/test-attachments"])
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class IssueMoveTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val uploads: IssueAttachmentAPI,
    @Autowired val issues: IssueRepository,
    @Autowired val attachments: IssueAttachmentRepository,
    @Autowired val observers: IssueObserverRepository,
    @Autowired val links: IssueLinkRepository,
    @Autowired val news: IssueNewsRepository,
    @Autowired val agents: AgentRepository,
    @Autowired val users: AppUserRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
    @Autowired val properties: AttachmentProperties,
    @Autowired val settings: InstallationSettingRepository,
) {

    private var supportId: Long = 0
    private var billingId: Long = 0
    private var claudeId: Long = 0

    @BeforeEach
    fun reset() {
        news.deleteAll()
        attachments.deleteAll()
        observers.deleteAll()
        links.deleteAll()
        issues.deleteAll()
        agents.deleteAll()
        audit.deleteAll()
        /*
         * The switch that governs attachments is held in a row, and this class
         * shares its context with the one that turns them off - so a run that
         * did not clear it would find the files refused here for a reason that
         * has nothing to do with moving anything.
         */
        settings.deleteAll()
        workspaces.deleteAll()
        users.deleteAll()
        supportId = requireNotNull(workspaces.save(Workspace(name = "support")).id)
        billingId = requireNotNull(workspaces.save(Workspace(name = "billing")).id)
        claudeId = requireNotNull(
            users.save(AppUser(username = "claude", displayName = "Claude", type = UserType.INTERNAL)).id,
        )
    }

    private fun file(title: String, workspace: Long = supportId): Long =
        graphQlTester.document(
            """mutation { createIssue(input: { workspaceId: $workspace, title: "$title" }) { id } }""",
        ).execute().path("createIssue.id").entity(Long::class.java).get()

    private fun move(id: Long, to: Long) =
        graphQlTester.document("""mutation { moveIssue(id: $id, workspaceId: $to) { id workspaceId number title } }""")
            .execute()

    /** Uploads one and hands back the row it made, the way the tracker's own does. */
    private fun upload(name: String, workspace: Long = supportId): IssueAttachment {
        val before = attachments.findAll().mapNotNull { it.id }.toSet()
        uploads.upload(workspace, listOf(MockMultipartFile("files", name, "image/png", "a screenshot".toByteArray())))
        return attachments.findAll().first { it.id !in before }
    }

    private fun onDisk(location: String): Path =
        Path.of(properties.location).toAbsolutePath().normalize().resolve(location)

    @Test
    fun `an administrator moves an issue and it takes a number that is free where it lands`() {
        file("Billing is wrong")
        val moving = file("The invoice is late")
        // Two already in billing, so a free number there is 3 rather than the 2
        // this issue has where it is.
        file("The card was declined", workspace = billingId)
        file("The total does not add up", workspace = billingId)

        move(moving, billingId)
            .path("moveIssue.workspaceId").entity(Long::class.java).isEqualTo(billingId)
            .path("moveIssue.number").entity(Int::class.java).isEqualTo(3)
            .path("moveIssue.title").entity(String::class.java).isEqualTo("The invoice is late")

        // Both trackers say what happened, in the words each of them needs.
        assertThat(audit.findAll().filter { it.workspaceId == supportId }.map { it.message })
            .anySatisfy { assertThat(it).contains("moved to billing as #3") }
        assertThat(audit.findAll().filter { it.workspaceId == billingId }.map { it.message })
            .anySatisfy { assertThat(it).contains("moved here from support") }
    }

    @Test
    fun `it leaves the list it came from and appears in the one it went to`() {
        val moving = file("The invoice is late")
        file("The reply is late")

        move(moving, billingId).path("moveIssue.id").hasValue()

        graphQlTester.document("""{ workspaceIssues(workspaceId: $supportId) { totalElements content { title } } }""")
            .execute()
            .path("workspaceIssues.totalElements").entity(Int::class.java).isEqualTo(1)
            .path("workspaceIssues.content[0].title").entity(String::class.java).isEqualTo("The reply is late")

        graphQlTester.document("""{ workspaceIssues(workspaceId: $billingId) { totalElements content { title } } }""")
            .execute()
            .path("workspaceIssues.totalElements").entity(Int::class.java).isEqualTo(1)
            .path("workspaceIssues.content[0].title").entity(String::class.java).isEqualTo("The invoice is late")

        /*
         * And the address it used to answer to answers nothing. That is the
         * honest reading rather than a gap worth papering over: the number it
         * had is free for the next issue filed here, so a redirect left behind
         * would sooner or later point away from a live issue.
         */
        graphQlTester.document("""{ workspaceIssue(workspaceId: $supportId, number: 1) { title } }""")
            .execute()
            .path("workspaceIssue").valueIsNull()
    }

    @Test
    fun `the conversation, the labels and the links come with it`() {
        val moving = file("The invoice is late")
        graphQlTester.document("""mutation { commentOnIssue(id: $moving, content: "Looking at it now") { id } }""")
            .execute().path("commentOnIssue.id").hasValue()
        graphQlTester.document(
            """mutation { updateIssue(id: $moving, input: { labels: ["urgent"] }) { id } }""",
        ).execute().path("updateIssue.id").hasValue()
        graphQlTester.document(
            """mutation { addIssueLink(id: $moving, url: "https://example.com/invoice") { id } }""",
        ).execute().path("addIssueLink.id").hasValue()

        move(moving, billingId).path("moveIssue.number").entity(Int::class.java).isEqualTo(1)

        graphQlTester.document(
            """{ workspaceIssue(workspaceId: $billingId, number: 1) {
                 labels links { url } comments { author content }
               } }""",
        ).execute()
            .path("workspaceIssue.labels").entityList(String::class.java).containsExactly("urgent")
            .path("workspaceIssue.links[0].url").entity(String::class.java).isEqualTo("https://example.com/invoice")
            .path("workspaceIssue.comments[0].content").entity(String::class.java).isEqualTo("Looking at it now")
            /*
             * And the move itself is written into the conversation. The old
             * number is spelled out in words rather than as a hash and digits,
             * because the shorthand would be rendered as a link to whatever
             * holds that number in the workspace it has just arrived in.
             */
            .path("workspaceIssue.comments[1].content").entity(String::class.java)
            .isEqualTo("Moved here from support, where it was number 1.")
    }

    @Test
    fun `the files go with it, bytes and all, so they still open where it lands`() {
        val moving = file("The invoice is late")
        val uploaded = upload("screenshot.png")
        graphQlTester.document("""mutation { attachToIssue(id: $moving, attachmentIds: [${uploaded.id}]) { id } }""")
            .execute().path("attachToIssue.id").hasValue()
        val was = uploaded.location
        assertThat(Files.exists(onDisk(was))).isTrue()

        move(moving, billingId).path("moveIssue.number").entity(Int::class.java).isEqualTo(1)

        val carried = requireNotNull(attachments.findByIdOrNull(requireNotNull(uploaded.id)))
        // The row and the disk say the same thing, which is what makes the file
        // reachable: the workspace's folder is what decides who may open it.
        assertThat(carried.workspaceId).isEqualTo(billingId)
        assertThat(carried.location).startsWith("$billingId/")
        assertThat(Files.exists(onDisk(carried.location))).isTrue()
        assertThat(Files.exists(onDisk(was))).isFalse()

        graphQlTester.document("""{ workspaceIssue(workspaceId: $billingId, number: 1) { attachments { filename } } }""")
            .execute()
            .path("workspaceIssue.attachments[0].filename").entity(String::class.java).isEqualTo("screenshot.png")
    }

    @Test
    @WithMockUser(username = "bob", roles = ["SUPPORT"])
    fun `somebody who cannot even see the workspace is told the issue is not there`() {
        // Filed through the repository, since bob cannot see either workspace
        // and this is about the move rather than about the filing.
        val moving = requireNotNull(
            issues.save(Issue(workspaceId = supportId, number = 1, title = "The invoice is late", reporter = "alice")).id,
        )

        // Not "you need a role": an issue in a workspace bob cannot see reads as
        // one that is not there, so walking the ids says nothing about what
        // exists here.
        move(moving, billingId)
            .errors().expect { it.message == "No issue with id $moving" }
            .verify()

        assertThat(requireNotNull(issues.findByIdOrNull(moving)).workspaceId).isEqualTo(supportId)
    }

    @Test
    fun `an issue held by an agent of the workspace it is leaving is refused rather than quietly orphaned`() {
        val theirs = agents.save(Agent(workspaceId = supportId, name = "Support responder", type = AgentType.LLM))
        val moving = file("The invoice is late")
        graphQlTester.document(
            """mutation { updateIssue(id: $moving, input: { assigneeKind: AGENT, assigneeId: "${theirs.id}" }) { id } }""",
        ).execute().path("updateIssue.id").hasValue()

        move(moving, billingId)
            .errors().expect { it.message?.contains("assigned to Support responder, which is not in billing") == true }
            .verify()

        // Nothing moved: a refusal that had already half done it would be worse
        // than either answer.
        assertThat(requireNotNull(issues.findByIdOrNull(moving)).workspaceId).isEqualTo(supportId)
    }

    @Test
    fun `an observer that could not follow stops the move, and taking it off lets it through`() {
        val theirs = agents.save(Agent(workspaceId = supportId, name = "Support responder", type = AgentType.LLM))
        val moving = file("The invoice is late")
        graphQlTester.document(
            """mutation { observeIssue(id: $moving, observerKind: AGENT, observerId: "${theirs.id}") { id } }""",
        ).execute().path("observeIssue.id").hasValue()

        move(moving, billingId)
            .errors().expect { it.message?.contains("Support responder would stop hearing") == true }
            .verify()

        graphQlTester.document(
            """mutation { unobserveIssue(id: $moving, observerKind: AGENT, observerId: "${theirs.id}") { id } }""",
        ).execute().path("unobserveIssue.id").hasValue()

        move(moving, billingId).path("moveIssue.workspaceId").entity(Long::class.java).isEqualTo(billingId)
    }

    @Test
    fun `a person keeps the issue across the move, because a person is not a workspace's`() {
        val moving = file("The invoice is late")
        graphQlTester.document(
            """mutation { updateIssue(id: $moving, input: { assigneeKind: USER, assigneeId: "$claudeId" }) { id } }""",
        ).execute().path("updateIssue.id").hasValue()

        /*
         * Nothing in the way, because users are the installation's rather than a
         * workspace's - so the person doing the work goes on doing it, and the
         * `@name` written into a comment still names them.
         */
        move(moving, billingId)
            .path("moveIssue.workspaceId").entity(Long::class.java).isEqualTo(billingId)

        graphQlTester.document("""{ workspaceIssue(workspaceId: $billingId, number: 1) { assignee { name } } }""")
            .execute()
            .path("workspaceIssue.assignee.name").entity(String::class.java).isEqualTo("Claude")
    }

    @Test
    fun `an issue cannot be moved to the workspace it is already in`() {
        val moving = file("The invoice is late")

        move(moving, supportId)
            .errors().expect { it.message?.contains("already in support") == true }
            .verify()
    }

    @Test
    fun `the news it made before the move is left where it was announced`() {
        val moving = file("The invoice is late")
        // Handed to somebody who is not the person doing the moving, since the
        // desk never tells anybody about their own doing.
        graphQlTester.document(
            """mutation { updateIssue(id: $moving, input: { assigneeKind: USER, assigneeId: "$claudeId" }) { id } }""",
        ).execute().path("updateIssue.id").hasValue()
        graphQlTester.document("""mutation { commentOnIssue(id: $moving, content: "Looking at it now") { id } }""")
            .execute().path("commentOnIssue.id").hasValue()
        val announced = news.findAll().count { it.workspaceId == supportId }
        assertThat(announced).isGreaterThan(0)

        move(moving, billingId).path("moveIssue.id").hasValue()

        /*
         * What was said in support was said in support. Rewriting it would move
         * somebody's unread items into a workspace they may not be able to see,
         * and each item would land either side of a read mark that is kept per
         * workspace - so the same thing would either vanish unread or arrive a
         * second time.
         */
        assertThat(news.findAll().count { it.workspaceId == supportId }).isEqualTo(announced)
        // The move itself is announced where the issue now is, so whoever was
        // following it is told once, in the place they will find it.
        assertThat(news.findAll().filter { it.workspaceId == billingId }.map { it.says })
            .anySatisfy { assertThat(it).contains("Moved here from support") }
    }
}
