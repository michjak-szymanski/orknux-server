package io.mszymanski.orknux.server.obj

import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser

/**
 * The object catalogue: what a workspace may describe, and what it may point at.
 *
 * The rules worth holding are the ones a screen cannot enforce — a reference to
 * another workspace's object, and deleting a shape something else still claims
 * to be.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class ObjectAPITest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val objects: WorkflowObjectRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private var workspaceId: Long = 0

    @BeforeEach
    fun reset() {
        objects.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
    }

    @Test
    fun `an object holds its properties in order, and says how each one reads`() {
        val fileId = create("FileObject")
        val id = graphQlTester.document(
            """
            mutation {
              createObject(input: {
                workspaceId: $workspaceId, name: "SlackMessage",
                description: "Represents an incoming Slack message with metadata",
                properties: [
                  { name: "channel", kind: STRING },
                  { name: "timestamp", kind: NUMBER },
                  { name: "isBot", kind: BOOLEAN },
                  { name: "attachments", kind: ARRAY, refObjectId: $fileId },
                  { name: "metadata", kind: OBJECT, refObjectId: $fileId }
                ]
              }) { id propertyCount properties { name display } }
            }
            """,
        ).execute()
            .path("createObject.propertyCount").entity(Int::class.java).isEqualTo(5)
            // The type is assembled here, not on the screen, and a reference
            // reads as the name that object has now.
            .path("createObject.properties[0].display").entity(String::class.java).isEqualTo("string")
            .path("createObject.properties[3].display").entity(String::class.java).isEqualTo("array<FileObject>")
            .path("createObject.properties[4].display").entity(String::class.java).isEqualTo("FileObject")
            .path("createObject.id").entity(Long::class.java).get()

        // Order is the editor's, so the screen shows them back as they were typed.
        assertThat(objects.findAll().single { it.id == id }.properties.map { it.name })
            .containsExactly("channel", "timestamp", "isBot", "attachments", "metadata")
        assertThat(audit.findAll().map { it.message }).contains("Object SlackMessage created")
    }

    @Test
    fun `an object cannot point at another workspace's shape`() {
        val other = requireNotNull(workspaces.save(Workspace(name = "frontend")).id)
        val theirs = graphQlTester.document(
            """mutation { createObject(input: { workspaceId: $other, name: "Secret" }) { id } }""",
        ).execute().path("createObject.id").entity(Long::class.java).get()

        graphQlTester.document(
            """
            mutation {
              createObject(input: {
                workspaceId: $workspaceId, name: "Borrowed",
                properties: [{ name: "leak", kind: OBJECT, refObjectId: $theirs }]
              }) { id }
            }
            """,
        ).execute().errors().satisfy { errors ->
            assertThat(errors.first().message).contains("does not have")
        }
    }

    @Test
    fun `a shape something else claims to be is not deleted`() {
        val fileId = create("FileObject")
        graphQlTester.document(
            """
            mutation {
              createObject(input: {
                workspaceId: $workspaceId, name: "SlackMessage",
                properties: [{ name: "attachments", kind: ARRAY, refObjectId: $fileId }]
              }) { id }
            }
            """,
        ).execute()

        graphQlTester.document("""mutation { deleteObject(id: $fileId) }""")
            .execute()
            .errors().satisfy { errors ->
                assertThat(errors.first().message).contains("SlackMessage")
            }

        // Still there, so nothing is left describing a shape that is gone.
        assertThat(objects.findAll().map { it.name }).contains("FileObject")
    }

    /**
     * An object may refer to itself: that is how a tree is described, and the
     * check that stops cross-workspace references must not stand in its way.
     */
    @Test
    fun `an object may hold one of itself`() {
        val id = create("Node")

        graphQlTester.document(
            """
            mutation {
              updateObject(id: $id, input: {
                properties: [
                  { name: "label", kind: STRING },
                  { name: "children", kind: ARRAY, refObjectId: $id }
                ]
              }) { properties { display } }
            }
            """,
        ).execute()
            .path("updateObject.properties[1].display").entity(String::class.java).isEqualTo("array<Node>")
    }

    @Test
    fun `a name a mapping could not be written with is refused`() {
        graphQlTester.document(
            """mutation { createObject(input: { workspaceId: $workspaceId, name: "Slack Message" }) { id } }""",
        ).execute().errors().satisfy { errors ->
            assertThat(errors.first().message).contains("cannot be used as a name")
        }
    }

    /** Validate answers; a half-written property is what the button is for. */
    @Test
    fun `validate reports what is wrong rather than failing`() {
        graphQlTester.document(
            """
            mutation {
              validateObject(workspaceId: $workspaceId, properties: [
                { name: "channel", kind: STRING },
                { name: "attachments", kind: ARRAY }
              ]) { valid message }
            }
            """,
        ).execute()
            .path("validateObject.valid").entity(Boolean::class.java).isEqualTo(false)
            .path("validateObject.message").entity(String::class.java).isEqualTo("attachments is an array but does not say of what")

        graphQlTester.document(
            """
            mutation {
              validateObject(workspaceId: $workspaceId, properties: [{ name: "channel", kind: STRING }]) {
                valid message
              }
            }
            """,
        ).execute().path("validateObject.valid").entity(Boolean::class.java).isEqualTo(true)
    }

    private fun create(name: String): Long = graphQlTester.document(
        """mutation { createObject(input: { workspaceId: $workspaceId, name: "$name" }) { id } }""",
    ).execute().path("createObject.id").entity(Long::class.java).get()
}
