package io.mszymanski.orknux.server.plugin

import io.mszymanski.orknux.server.action.ValueType
import io.mszymanski.orknux.server.action.WorkflowFunctionRepository
import io.mszymanski.orknux.server.obj.ObjectProperty
import io.mszymanski.orknux.server.obj.PropertyKind
import io.mszymanski.orknux.server.obj.WorkflowObject
import io.mszymanski.orknux.server.obj.WorkflowObjectRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.test.context.support.WithMockUser

/**
 * A plugin that exports shapes, from the file to the row a workspace points at.
 *
 * Six claims carry the feature, and each is a test: the shapes become rows
 * under the plugin's key; a reference the plugin wrote by name becomes a
 * reference by id, in both directions a shape can point; a name pointing at
 * nothing is refused at load; re-loading replaces the set rather than adding
 * to it; a shape a workspace still points at is not taken away quietly; a
 * function can return one, which is what exporting them is for; and a plugin's
 * shape is the plugin's — there is nowhere to edit one.
 */
@SpringBootTest
@WithMockUser(username = "alice", roles = ["ADMINS"])
class PluginObjectsTest(
    @Autowired val upload: PluginUploadAPI,
    @Autowired val plugins: PluginRepository,
    @Autowired val objects: WorkflowObjectRepository,
    @Autowired val functions: WorkflowFunctionRepository,
    @Autowired val declarations: PluginDeclarations,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private var workspaceId: Long = 0

    @BeforeEach
    fun reset() {
        plugins.deleteAll()
        functions.deleteAll()
        objects.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
    }

    /** A tracker plugin, whose two shapes point at each other's kind. */
    private val source = """
        export default class Jira extends OrknuxPlugin {
          id() { return 'jira'; }
          apiVersion() { return 1; }
          objects() {
            return [
              new OrknuxObject({
                name: 'User',
                properties: [{ name: 'email', kind: 'string', description: 'Who they are.' }],
              }),
              new OrknuxObject({
                name: 'Issue',
                description: 'One tracker issue.',
                properties: [
                  { name: 'key', kind: 'string' },
                  { name: 'labels', kind: 'array', of: 'string' },
                  { name: 'reporter', kind: 'object', of: 'User' },
                  { name: 'watchers', kind: 'array', of: 'User' },
                ],
              }),
            ];
          }
        }
    """.trimIndent()

    private fun load(text: String = source, name: String = "jira.js") =
        upload.upload(MockMultipartFile("file", name, "text/javascript", text.toByteArray()), null, null)

    @Test
    fun `the shapes become rows under the plugin's key`() {
        load()

        val exported = objects.findByPluginIdIsNotNull().sortedBy { it.name }
        assertThat(exported.map { it.name }).containsExactly("jira_Issue", "jira_User")
        assertThat(exported.first { it.name == "jira_Issue" }.description).isEqualTo("One tracker issue.")
        // Installation-wide, the way a plugin's functions are.
        assertThat(exported).allSatisfy { assertThat(it.workspaceId).isNull() }
    }

    /**
     * The plugin writes `of: 'User'` and the row holds an id. That translation
     * is the whole reason this is a registry rather than a column of JSON: a
     * reference by id is what everything downstream already follows.
     */
    @Test
    fun `a reference written by name becomes a reference by id`() {
        load()

        val user = objects.findByPluginIdIsNotNull().single { it.name == "jira_User" }
        val issue = objects.findByPluginIdIsNotNull().single { it.name == "jira_Issue" }

        val reporter = issue.properties.single { it.name == "reporter" }
        assertThat(reporter.kind).isEqualTo(PropertyKind.OBJECT)
        assertThat(reporter.refObjectId).isEqualTo(user.id)

        // An array says what it holds two ways, and both survive the trip.
        val labels = issue.properties.single { it.name == "labels" }
        assertThat(labels.kind).isEqualTo(PropertyKind.ARRAY)
        assertThat(labels.elementKind).describedAs("a scalar element is a kind").isEqualTo(PropertyKind.STRING)
        assertThat(labels.refObjectId).isNull()

        val watchers = issue.properties.single { it.name == "watchers" }
        assertThat(watchers.kind).isEqualTo(PropertyKind.ARRAY)
        assertThat(watchers.refObjectId).describedAs("an object element is a reference").isEqualTo(user.id)

        // And the description survives, which is the half a model reads.
        assertThat(user.properties.single().description).isEqualTo("Who they are.")
    }

    @Test
    fun `a name that points at nothing is refused at load, and nothing is stored`() {
        val broken = source.replace("of: 'User'", "of: 'Person'")

        val failure = runCatching { load(broken) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(PluginDeclarationInvalidException::class.java)
        assertThat(failure!!.message).contains("points at \"Person\"").contains("objects() does not declare")
        assertThat(plugins.findByKey("jira")).isNull()
        assertThat(objects.findByPluginIdIsNotNull()).isEmpty()
    }

    /**
     * What the plugin declares now is what exists afterwards. Loading the same
     * plugin twice leaves the same set behind, and a shape it has dropped is
     * gone rather than left as a row nothing maintains.
     */
    @Test
    fun `re-loading replaces the set rather than adding to it`() {
        load()
        // The same plugin, one shape lighter and with the references to it gone.
        val dropped = """
            export default class Jira extends OrknuxPlugin {
              id() { return 'jira'; }
              apiVersion() { return 1; }
              objects() {
                return [
                  new OrknuxObject({
                    name: 'Issue',
                    properties: [
                      { name: 'key', kind: 'string' },
                      { name: 'labels', kind: 'array', of: 'string' },
                    ],
                  }),
                ];
              }
            }
        """.trimIndent()

        load(dropped)

        assertThat(objects.findByPluginIdIsNotNull().map { it.name }).containsExactly("jira_Issue")
        assertThat(objects.findByPluginIdIsNotNull().single().properties.map { it.name })
            .containsExactly("key", "labels")
    }

    /**
     * A property whose reference was deleted out from under it describes
     * nothing, and says so at the moment a run needs it. So it is refused
     * while somebody is still looking at the screen.
     */
    @Test
    fun `a shape a workspace still points at is not taken away quietly`() {
        load()
        val user = objects.findByPluginIdIsNotNull().single { it.name == "jira_User" }
        objects.save(
            WorkflowObject(
                workspaceId = workspaceId,
                name = "Review",
                properties = mutableListOf(
                    ObjectProperty(name = "author", kind = PropertyKind.OBJECT, refObjectId = user.id),
                ),
            ),
        )

        val withoutUser = source.replace("name: 'User'", "name: 'Account'")
            .replace("of: 'User'", "of: 'Account'")
        val failure = runCatching { load(withoutUser) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(PluginObjectInUseException::class.java)
        assertThat(failure!!.message).contains("jira_User").contains("Review")
    }

    /**
     * The point of exporting a shape: a function can say it returns one.
     *
     * The plugin writes `returnType: 'Issue'` and the function row holds a
     * reference to the row the shape became - so everything that already
     * follows one of those references follows this one, and a workflow calling
     * the function knows what comes back.
     */
    @Test
    fun `a function returns an exported shape, and the row points at it`() {
        val returning = """
            export default class Jira extends OrknuxPlugin {
              id() { return 'jira'; }
              apiVersion() { return 1; }
              objects() {
                return [
                  new OrknuxObject({
                    name: 'Issue',
                    properties: [{ name: 'key', kind: 'string' }],
                  }),
                ];
              }
              functions() {
                return [new OrknuxFunction({
                  name: 'openIssue',
                  params: [{ name: 'key', type: 'string' }],
                  returnType: 'Issue',
                  run: (key) => ({ key }),
                })];
              }
              tools() {
                return [new OrknuxFunctionTool({ function: 'openIssue' })];
              }
            }
        """.trimIndent()

        load(returning)

        val issue = objects.findByPluginIdIsNotNull().single { it.name == "jira_Issue" }
        val function = functions.findAll().single { it.name == "jira_openIssue" }
        assertThat(function.returnType).isEqualTo(ValueType.OBJECT)
        assertThat(function.returnObjectId)
            .describedAs("the name became the reference")
            .isEqualTo(issue.id)

        // And it reads as the shape's name rather than as "object", which
        // says nothing and is the reason the name was declared.
        val declared = declarations.read(plugins.findByKey("jira")!!.declaredFunctions).single()
        assertThat(declared.returnObject).isEqualTo("Issue")
        assertThat(declared.signature).isEqualTo("(key: string): Issue")

        // A tool fronting it inherits the shape, so a model is answered with
        // named fields rather than an unexplained map.
        val tool = declarations.readTools(plugins.findByKey("jira")!!.declaredTools).single()
        assertThat(tool.returnObject).isEqualTo("Issue")
    }

    @Test
    fun `a returnType naming no shape is refused, and says what was on offer`() {
        val wrong = """
            export default class Jira extends OrknuxPlugin {
              id() { return 'jira'; }
              apiVersion() { return 1; }
              objects() {
                return [new OrknuxObject({ name: 'Issue', properties: [{ name: 'key', kind: 'string' }] })];
              }
              functions() {
                return [new OrknuxFunction({
                  name: 'openTicket',
                  params: [],
                  returnType: 'Ticket',
                  run: () => ({}),
                })];
              }
            }
        """.trimIndent()

        val failure = runCatching { load(wrong) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(PluginDeclarationInvalidException::class.java)
        assertThat(failure!!.message)
            .contains("neither a type this server has nor a shape this plugin exports")
        assertThat(plugins.findByKey("jira")).isNull()
    }

    @Test
    fun `a plugin's shape is the plugin's, and the declaration is kept as the plugin wrote it`() {
        load()

        // Unprefixed on the plugin, prefixed on the row: the declaration is
        // what the plugin said, and the row is what the server made of it.
        val declared = plugins.findByKey("jira")!!.declaredObjects
        assertThat(declared).contains("\"name\":\"Issue\"").doesNotContain("jira_Issue")
        assertThat(declared).describedAs("references stay as names until there are ids").contains("\"of\":\"User\"")
    }
}
