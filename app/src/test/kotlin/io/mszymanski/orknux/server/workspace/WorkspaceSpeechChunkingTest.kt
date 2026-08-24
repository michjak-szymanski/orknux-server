package io.mszymanski.orknux.server.workspace

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser

/**
 * Where an answer is cut for the speech provider, as the API offers it.
 *
 * The trade it names has no right answer: cutting at sentence ends is what gets
 * a first word out at the speed a person would start talking, and it is also
 * what puts a join between every sentence and a request behind every join.
 * Which of those matters is a fact about the listener, so a workspace says.
 *
 * The assertion that carries this file is the first one. Every workspace that
 * exists was read to a sentence at a time before this column did, and an
 * upgrade that quietly moved any of them to something else would be this change
 * breaking the thing it was meant to make optional - so the default is asserted
 * on a row nobody has touched, on the way out through the API rather than only
 * in the entity's initialiser.
 *
 * The other half is that there is no fourth value. `SENTENCE` is the default
 * *and* one of the three things to choose, which is what lets the form draw
 * three named options rather than three and a "Default" that is a second
 * spelling of one of them; nothing here may report the default as null.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class WorkspaceSpeechChunkingTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private var workspaceId: Long = 0

    @BeforeEach
    fun reset() {
        audit.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
    }

    /**
     * A workspace nobody has touched is read to a sentence at a time.
     *
     * Which is what it was already doing, and the whole of what an upgrade is
     * allowed to change here: nothing.
     */
    @Test
    fun `a workspace that has decided nothing is read a sentence at a time`() {
        graphQlTester.document("query { workspace(id: $workspaceId) { voiceSpeechChunking } }")
            .execute()
            .path("workspace.voiceSpeechChunking").entity(String::class.java).isEqualTo("SENTENCE")

        assertThat(workspaces.findAll().single().voiceSpeechChunking).isEqualTo(SpeechChunking.SENTENCE)
    }

    /** Each of the three round-trips, from the mutation, from the row and from a fresh read. */
    @Test
    fun `each of the three round-trips`() {
        for (chosen in SpeechChunking.entries) {
            graphQlTester.document(
                "mutation { setWorkspaceVoiceSpeechChunking(workspaceId: $workspaceId, chunking: $chosen) " +
                    "{ voiceSpeechChunking } }",
            ).execute()
                .path("setWorkspaceVoiceSpeechChunking.voiceSpeechChunking")
                .entity(String::class.java).isEqualTo(chosen.name)

            assertThat(workspaces.findAll().single().voiceSpeechChunking).isEqualTo(chosen)

            graphQlTester.document("query { workspace(id: $workspaceId) { voiceSpeechChunking } }")
                .execute()
                .path("workspace.voiceSpeechChunking").entity(String::class.java).isEqualTo(chosen.name)
        }
    }

    /**
     * Choosing the default explicitly is a choice, not a no-op.
     *
     * It is what somebody who has tried the other two presses to come back, so
     * it has to be storable from wherever they were rather than only reachable
     * by never having decided anything.
     */
    @Test
    fun `it can be set back to the default from either of the others`() {
        set(SpeechChunking.NONE)
        set(SpeechChunking.SENTENCE)
        assertThat(workspaces.findAll().single().voiceSpeechChunking).isEqualTo(SpeechChunking.SENTENCE)

        set(SpeechChunking.PARAGRAPH)
        set(SpeechChunking.SENTENCE)
        assertThat(workspaces.findAll().single().voiceSpeechChunking).isEqualTo(SpeechChunking.SENTENCE)
    }

    /**
     * Nothing outside the three reaches the resolver at all.
     *
     * An enum rather than a string is the whole of the validation, and this is
     * what says so: a fourth value is a request that never arrives, and the
     * workspace is left where it was.
     */
    @Test
    fun `a value that is not one of the three is refused`() {
        set(SpeechChunking.PARAGRAPH)

        graphQlTester.document(
            "mutation { setWorkspaceVoiceSpeechChunking(workspaceId: $workspaceId, chunking: HALF_A_LINE) { id } }",
        ).execute().errors().satisfy { errors -> assertThat(errors).isNotEmpty() }

        assertThat(workspaces.findAll().single().voiceSpeechChunking).isEqualTo(SpeechChunking.PARAGRAPH)
    }

    /**
     * Each choice is worth a line, under the category a chat's own behaviour
     * belongs to.
     *
     * CHAT rather than MODEL, for the reason turn-taking is: the speech model
     * did not change, what a chat does with its answers did - and somebody
     * asking why answers started sounding different is reading the chat's
     * history.
     */
    @Test
    fun `each choice is recorded under CHAT, in words`() {
        set(SpeechChunking.NONE)
        set(SpeechChunking.PARAGRAPH)
        set(SpeechChunking.SENTENCE)

        val chat = audit.findAll().filter { it.category == WorkspaceAuditCategory.CHAT }.map { it.message }
        assertThat(chat).containsExactlyInAnyOrder(
            "Answers are read aloud in one piece",
            "Answers are read aloud a paragraph at a time",
            "Answers are read aloud a sentence at a time",
        )
    }

    private fun set(chosen: SpeechChunking) {
        graphQlTester.document(
            "mutation { setWorkspaceVoiceSpeechChunking(workspaceId: $workspaceId, chunking: $chosen) { id } }",
        ).execute().path("setWorkspaceVoiceSpeechChunking.id").hasValue()
    }
}
