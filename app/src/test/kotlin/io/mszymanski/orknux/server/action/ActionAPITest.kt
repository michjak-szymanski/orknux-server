package io.mszymanski.orknux.server.action

import io.mszymanski.orknux.connector.connection.ConnectionRepository
import io.mszymanski.orknux.connector.connection.WorkspaceConnectionRepository
import io.mszymanski.orknux.server.condition.WorkflowConditionRepository
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
 * The action catalogue. What an action needs and what it produces are read off
 * its settings rather than stored, so most of what is asserted here is that
 * those come out right for each kind of action.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class ActionAPITest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val actions: WorkflowActionRepository,
    @Autowired val functions: WorkflowFunctionRepository,
    @Autowired val conditions: WorkflowConditionRepository,
    @Autowired val workspaceConnections: WorkspaceConnectionRepository,
    @Autowired val connections: ConnectionRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private var workspaceId: Long = 0
    private var connectionId: Long = 0

    @BeforeEach
    fun reset() {
        actions.deleteAll()
        // A condition that asks a function holds it, so conditions go first.
        conditions.deleteAll()
        functions.deleteAll()
        workspaceConnections.deleteAll()
        connections.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()

        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
        connectionId = graphQlTester.document(
            """
            mutation {
              createWorkspaceConnection(input: {
                workspaceId: $workspaceId, name: "Slack", type: SLACK_SOCKET_MODE, url: "https://slack.com/api"
              }) { id }
            }
            """,
        ).execute().path("createWorkspaceConnection.id").entity(Long::class.java).get()
    }

    @Test
    fun `a message action takes what its content asks for and answers with a status`() {
        graphQlTester.document(
            """
            mutation {
              createAction(input: {
                workspaceId: $workspaceId, name: "Send Slack Notification", type: EXECUTE, subtype: OUTGOING_CONNECTION,
                connectionId: $connectionId, connectionAction: SEND_MESSAGE,
                content: "Hello {{input.name}}, your request {{input.id}} is approved",
                target: CHANNEL, targetName: "#notifications"
              }) { subtypeLabel connectionName inputParams { display } outputParams { display } }
            }
            """,
        ).execute()
            .path("createAction.subtypeLabel").entity(String::class.java).isEqualTo("Outgoing Connection")
            .path("createAction.connectionName").entity(String::class.java).isEqualTo("Slack")
            .path("createAction.inputParams[*].display").entityList(String::class.java)
            .containsExactly("name: string", "id: string")
            .path("createAction.outputParams[*].display").entityList(String::class.java)
            .containsExactly("status: boolean")

        assertThat(audit.findAll().map { it.message }).contains("Action Send Slack Notification created")
    }

    @Test
    fun `an HTTP action reads its inputs from the URL and the headers`() {
        graphQlTester.document(
            """
            mutation {
              createAction(input: {
                workspaceId: $workspaceId, name: "Fetch API Data", type: EXECUTE, subtype: HTTP_REQUEST,
                url: "https://api.example.com/{{input.endpoint}}", method: "get",
                headers: "{ \"Authorization\": \"Bearer {{input.token}}\" }"
              }) { method subtypeLabel inputParams { display } outputParams { display } }
            }
            """,
        ).execute()
            .path("createAction.method").entity(String::class.java).isEqualTo("GET")
            .path("createAction.subtypeLabel").entity(String::class.java).isEqualTo("HTTP Request")
            .path("createAction.inputParams[*].display").entityList(String::class.java)
            .containsExactly("endpoint: string", "token: string")
            .path("createAction.outputParams[*].display").entityList(String::class.java)
            .containsExactly("response: object")
    }

    @Test
    fun `a function action answers with what the function returns`() {
        val functionId = createFunction("transformPayload", "OBJECT")

        graphQlTester.document(
            """
            mutation {
              createAction(input: {
                workspaceId: $workspaceId, name: "Transform Data", type: EXECUTE, subtype: FUNCTION,
                functionId: $functionId,
                mappings: [
                  { argument: "input", expression: "{{input.payload}}" },
                  { argument: "format", expression: "{{input.format}}" }
                ]
              }) { functionName inputParams { display } outputParams { display } }
            }
            """,
        ).execute()
            .path("createAction.functionName").entity(String::class.java).isEqualTo("transformPayload")
            .path("createAction.inputParams[*].display").entityList(String::class.java)
            .containsExactly("payload: string", "format: string")
            .path("createAction.outputParams[*].display").entityList(String::class.java)
            .containsExactly("result: object")
    }

    @Test
    fun `a wait takes and answers with what its condition names`() {
        graphQlTester.document(
            """
            mutation {
              createAction(input: {
                workspaceId: $workspaceId, name: "Wait for Approval", type: WAIT, subtype: INLINE_CONDITION,
                conditionExpression: "input.approved === true", timeoutSeconds: 3600, retryIntervalSeconds: 30
              }) { subtypeLabel inputParams { display } outputParams { display } }
            }
            """,
        ).execute()
            .path("createAction.subtypeLabel").entity(String::class.java).isEqualTo("Inline Condition")
            .path("createAction.inputParams[*].display").entityList(String::class.java)
            .containsExactly("approved: boolean")
            .path("createAction.outputParams[*].display").entityList(String::class.java)
            .containsExactly("approved: boolean")
    }

    @Test
    fun `a delay takes a duration and answers with nothing`() {
        graphQlTester.document(
            """
            mutation {
              createAction(input: {
                workspaceId: $workspaceId, name: "Delay Timer", type: WAIT, subtype: TIME, durationSeconds: 60
              }) { inputParams { display } outputParams { display } }
            }
            """,
        ).execute()
            .path("createAction.inputParams[*].display").entityList(String::class.java)
            .containsExactly("duration: number")
            .path("createAction.outputParams").entityList(String::class.java).hasSize(0)
    }

    @Test
    fun `a subtype that belongs to the other type is refused`() {
        graphQlTester.document(
            """
            mutation {
              createAction(input: {
                workspaceId: $workspaceId, name: "Confused", type: WAIT, subtype: HTTP_REQUEST, url: "https://example.com"
              }) { id }
            }
            """,
        ).execute().errors().expect { it.message?.contains("cannot be http request") == true }.verify()
    }

    @Test
    fun `an action without the setting it runs on is refused`() {
        graphQlTester.document(
            """
            mutation {
              createAction(input: {
                workspaceId: $workspaceId, name: "Nowhere", type: EXECUTE, subtype: HTTP_REQUEST
              }) { id }
            }
            """,
        ).execute().errors().expect { it.message?.contains("needs a URL") == true }.verify()
    }

    @Test
    fun `a connection from another workspace is refused`() {
        val otherWorkspace = requireNotNull(workspaces.save(Workspace(name = "frontend")).id)
        val theirs = graphQlTester.document(
            """
            mutation {
              createWorkspaceConnection(input: {
                workspaceId: $otherWorkspace, name: "Theirs", type: SLACK, url: "https://slack.com/api"
              }) { id }
            }
            """,
        ).execute().path("createWorkspaceConnection.id").entity(Long::class.java).get()

        graphQlTester.document(
            """
            mutation {
              createAction(input: {
                workspaceId: $workspaceId, name: "Borrowed", type: EXECUTE, subtype: OUTGOING_CONNECTION,
                connectionId: $theirs, connectionAction: SEND_MESSAGE
              }) { id }
            }
            """,
        ).execute().errors().expect { it.message?.contains("a connection this workspace holds") == true }.verify()
    }

    @Test
    fun `renaming and deleting are recorded`() {
        val id = graphQlTester.document(
            """
            mutation {
              createAction(input: {
                workspaceId: $workspaceId, name: "Delay Timer", type: WAIT, subtype: TIME, durationSeconds: 60
              }) { id }
            }
            """,
        ).execute().path("createAction.id").entity(Long::class.java).get()

        graphQlTester.document(
            """mutation { updateAction(id: $id, input: { name: "Pause" }) { name } }""",
        ).execute().path("updateAction.name").entity(String::class.java).isEqualTo("Pause")

        graphQlTester.document("""mutation { deleteAction(id: $id) }""")
            .execute().path("deleteAction").entity(Boolean::class.java).isEqualTo(true)

        assertThat(actions.findAll()).isEmpty()
        assertThat(audit.findAll().map { it.message })
            .contains("Action Delay Timer renamed to Pause", "Action Pause deleted")
    }

    @Test
    fun `a duplicate name inside a workspace is refused`() {
        repeat(2) { attempt ->
            val execution = graphQlTester.document(
                """
                mutation {
                  createAction(input: {
                    workspaceId: $workspaceId, name: "Delay Timer", type: WAIT, subtype: TIME, durationSeconds: 60
                  }) { id }
                }
                """,
            ).execute()
            if (attempt == 1) {
                execution.errors().expect { it.message?.contains("already exists") == true }.verify()
            }
        }
    }

    private fun createFunction(name: String, returnType: String): Long = graphQlTester.document(
        """
        mutation {
          createFunction(input: { workspaceId: $workspaceId, name: "$name", returnType: $returnType }) { id }
        }
        """,
    ).execute().path("createFunction.id").entity(Long::class.java).get()
}
