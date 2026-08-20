package io.mszymanski.orknux.server.llm

import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.ParameterizedTypeReference
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.test.context.support.WithMockUser

/**
 * A session's identity, and reading one back.
 *
 * Identity is most of what is worth pinning down here, because the thing that
 * looks like a bug is the feature: two workflows landing in one session is the
 * point, and the tests say so out loud rather than leaving somebody to discover
 * it and "fix" it. The other half is the separator, which is what keeps two
 * callers who meant different sessions out of each other's conversation - the
 * one collision that is not intended.
 *
 * The rest is what the AI perspective's two screens ask for: a list that can be
 * searched and counted, and a transcript that can be searched, filtered and
 * turned round.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class LlmSessionTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val recorder: LlmSessionRecorder,
    @Autowired val sessions: LlmSessionRepository,
    @Autowired val events: LlmSessionEventRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private var workspaceId: Long = 0
    private var otherWorkspaceId: Long = 0

    @BeforeEach
    fun reset() {
        events.deleteAll()
        sessions.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()

        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
        otherWorkspaceId = requireNotNull(workspaces.save(Workspace(name = "support")).id)
    }

    /**
     * The whole reason a session is keyed rather than identified.
     *
     * Two runs, of two different workflows, weeks apart, that arrive at the same
     * halves are talking into one conversation. Nothing about a run is on the
     * row, which is what makes that true.
     */
    @Test
    fun `two callers that compute the same key are in the same session`() {
        val first = recorder.open(workspaceId, "issue", "42")
        val second = recorder.open(workspaceId, "issue", "42")

        assertThat(second).isEqualTo(first)
        assertThat(sessions.findAll()).hasSize(1)
        assertThat(sessions.findAll().single().sessionKey).isEqualTo("issue:42")
        assertThat(sessions.findAll().single().keyPrefix).isEqualTo("issue")
    }

    /**
     * The separator earns its place here.
     *
     * Joined bare, prefix "issue-" with key "42" would be the string
     * "issue-42" - the same session as somebody who used no prefix at all and
     * meant something else entirely. That is the one collision this must not
     * allow, and it is invisible without a test that names it.
     */
    @Test
    fun `a prefix is a namespace rather than a few more characters`() {
        val prefixed = recorder.open(workspaceId, "issue-", "42")
        val bare = recorder.open(workspaceId, null, "issue-42")

        assertThat(prefixed).isNotEqualTo(bare)
        assertThat(sessions.findAll().map { it.sessionKey })
            .containsExactlyInAnyOrder("issue-:42", "issue-42")
    }

    /** No prefix is the key alone, not a key with a colon in front of it. */
    @Test
    fun `a session without a prefix is keyed by its key`() {
        recorder.open(workspaceId, null, "thread-7")
        recorder.open(workspaceId, "   ", "thread-8")

        assertThat(sessions.findAll().map { it.sessionKey }).containsExactlyInAnyOrder("thread-7", "thread-8")
        assertThat(sessions.findAll().map { it.keyPrefix }).containsOnlyNulls()
    }

    /**
     * A key nobody can store is said so rather than trimmed to fit.
     *
     * Two long keys cut to the same three hundred characters would be one
     * session, and pouring two conversations into one quietly is worse than
     * refusing a mapping that has to be shortened.
     */
    @Test
    fun `a key that names nothing, and one that will not fit, are both refused`() {
        assertThrows<LlmSessionKeyMissingException> { recorder.open(workspaceId, "issue", "   ") }
        assertThrows<LlmSessionKeyTooLongException> {
            recorder.open(workspaceId, "x".repeat(120), "y".repeat(200))
        }
        assertThat(sessions.findAll()).isEmpty()
    }

    /** The key is unique here and not everywhere: two teams may both say "standup". */
    @Test
    fun `the same key in two workspaces is two conversations`() {
        val ours = recorder.open(workspaceId, null, "standup")
        val theirs = recorder.open(otherWorkspaceId, null, "standup")

        assertThat(ours).isNotEqualTo(theirs)
        assertThat(sessions.findAll()).hasSize(2)
    }

    /**
     * All four kinds, read back as one transcript in the order they happened.
     *
     * One test rather than four, because the order is as much the subject as the
     * lines: a transcript is read as a conversation, and the question has to be
     * above the answer.
     */
    @Test
    fun `what was recorded is read back in the order it happened`() {
        val session = recorder.open(workspaceId, "issue", "42")
        recorder.userSaid(session, "Ask reviewer", "Why is the reply late?")
        recorder.toolCalled(session, "skill_load", """{"name":"codeReview"}""")
        recorder.agentSaid(session, "Reviewer", "The database was the cause.")
        recorder.note(session, "Reviewer could not answer: the model refused")

        val lines = events(session)
        assertThat(lines.map { it["kind"] }).containsExactly("USER", "TOOL", "AGENT", "SYSTEM")
        assertThat(lines.map { it["actor"] })
            .containsExactly("Ask reviewer", "skill_load", "Reviewer", "system")
        assertThat(lines[1]["content"] as String).contains("codeReview")

        // The session's own clock moved with the last of them, which is what the
        // list is ordered by.
        assertThat(requireNotNull(sessions.findAll().single().lastEventAt)).isNotNull()
    }

    /** The list page: searched across the key and the prefix, counted per row. */
    @Test
    fun `the list is searched and says how much each session holds`() {
        val incident = recorder.open(workspaceId, "issue", "42")
        recorder.userSaid(incident, "Ask reviewer", "Why is the reply late?")
        recorder.agentSaid(incident, "Reviewer", "The database was the cause.")
        recorder.open(workspaceId, "thread", "C123")

        val all = list()
        assertThat(all).hasSize(2)
        assertThat(all.single { it["key"] == "issue:42" }["eventCount"]).isEqualTo(2)
        // A session opened and not written to says nothing rather than nothing
        // at all: zero is an answer, and null would be a hole in the column.
        assertThat(all.single { it["key"] == "thread:C123" }["eventCount"]).isEqualTo(0)
        assertThat(all.single { it["key"] == "thread:C123" }["lastEventAt"]).isNull()

        assertThat(list(search = "issue").map { it["key"] }).containsExactly("issue:42")
        // The prefix is searched as well as the key, so a family can be found by
        // the name it was namespaced under.
        assertThat(list(search = "thread").map { it["key"] }).containsExactly("thread:C123")
        assertThat(list(search = "nothing here")).isEmpty()
    }

    /** The detail page: searched, filtered by kind, and turned round. */
    @Test
    fun `a transcript is searched, filtered and reversed`() {
        val session = recorder.open(workspaceId, "issue", "42")
        recorder.userSaid(session, "Ask reviewer", "Why is the reply late?")
        recorder.toolCalled(session, "skill_load", """{"name":"codeReview"}""")
        recorder.agentSaid(session, "Reviewer", "The database was the cause.")

        assertThat(events(session, search = "database").map { it["kind"] }).containsExactly("AGENT")
        // The actor is searched too, which is how somebody finds every call to
        // one tool in a long session.
        assertThat(events(session, search = "skill_load").map { it["kind"] }).containsExactly("TOOL")

        assertThat(events(session, kinds = "[TOOL, AGENT]").map { it["kind"] })
            .containsExactly("TOOL", "AGENT")
        // No kinds is every kind: a page that has cleared its checkboxes is
        // asking for everything, not for nothing.
        assertThat(events(session, kinds = "[]")).hasSize(3)

        assertThat(events(session, ascending = false).map { it["kind"] })
            .containsExactly("AGENT", "TOOL", "USER")
    }

    /**
     * Somebody who cannot see the workspace cannot read what its agents said.
     *
     * Asked by the session's own id, so the check has to be made against the
     * workspace on the row rather than one the caller names - otherwise any id
     * would be readable by anybody who can see any workspace at all.
     */
    @Test
    fun `a session in a workspace you cannot see is not there`() {
        val session = recorder.open(workspaceId, "issue", "42")
        recorder.agentSaid(session, "Reviewer", "The database was the cause.")

        asNobody {
            graphQlTester.document("{ llmSession(id: $session) { key } }")
                .execute()
                .path("llmSession")
                .valueIsNull()

            // The list refuses outright rather than answering with an empty one:
            // a workspace somebody cannot see is one they should be told nothing
            // about, including how many conversations it is not showing them.
            graphQlTester.document("{ llmSessions(workspaceId: $workspaceId) { totalElements } }")
                .execute()
                .errors()
                .satisfy { assertThat(it).isNotEmpty() }
        }
    }

    private fun list(search: String? = null): List<Map<String, Any?>> {
        val asked = if (search == null) "" else """, search: "$search""""
        return graphQlTester.document(
            """{ llmSessions(workspaceId: $workspaceId$asked) {
                   totalElements
                   content { id key keyPrefix eventCount createdAt lastEventAt }
                 } }""",
        ).execute()
            .path("llmSessions.content")
            .entity(object : ParameterizedTypeReference<List<Map<String, Any?>>>() {})
            .get()
    }

    private fun events(
        session: Long,
        search: String? = null,
        kinds: String? = null,
        ascending: Boolean? = null,
    ): List<Map<String, Any?>> {
        val asked = buildString {
            if (search != null) append(""", search: "$search"""")
            if (kinds != null) append(", kinds: $kinds")
            if (ascending != null) append(", ascending: $ascending")
        }
        return graphQlTester.document(
            """{ llmSessionEvents(sessionId: $session$asked) {
                   totalElements
                   content { id kind actor content at }
                 } }""",
        ).execute()
            .path("llmSessionEvents.content")
            .entity(object : ParameterizedTypeReference<List<Map<String, Any?>>>() {})
            .get()
    }

    /**
     * The same request from somebody holding nothing.
     *
     * A workspace with no roles is administrators only, so an authenticated
     * caller with no authorities is exactly the person this must refuse.
     */
    private fun <T> asNobody(block: () -> T): T {
        val held = SecurityContextHolder.getContext().authentication
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken("mallory", "n/a", emptyList())
        try {
            return block()
        } finally {
            SecurityContextHolder.getContext().authentication = held
        }
    }
}
