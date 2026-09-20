package io.mszymanski.orknux.server.workflow

import io.mszymanski.orknux.connector.model.ModelImageClient
import io.mszymanski.orknux.connector.model.Picture
import io.mszymanski.orknux.connector.model.ToolCall
import io.mszymanski.orknux.server.attachment.AttachmentStore
import io.mszymanski.orknux.server.attachment.InstallationSettings
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import tools.jackson.databind.ObjectMapper
import java.util.Optional

/**
 * The tool an agent inside a run is lent, and what it answers.
 *
 * What is pinned here is the three things that decide whether a picture happens
 * at all - an installation that keeps attachments, a workspace that has chosen
 * a model, and a description the model actually wrote - and the shape of the
 * answer, because the answer is what the next round reasons from. A refusal is
 * a sentence the agent reads and works around, never an exception that loses
 * the round. Issue #349.
 *
 * Whether the agent was granted pictures at all is decided one level up, in
 * [io.mszymanski.orknux.server.agent.AgentNodeRunner], because that is where
 * the agent is: this knows about a run and a workspace, not about whose round
 * it is.
 */
class StepPictureToolsTest {

    private val workspaces = mock(WorkspaceRepository::class.java)
    private val drawing = mock(ModelImageClient::class.java)
    private val pictures = mock(ExecutionPictureRepository::class.java)
    private val store = mock(AttachmentStore::class.java)
    private val settings = mock(InstallationSettings::class.java)
    /**
     * Where this installation is, which a picture's link has to carry: a model
     * pastes what it was handed, and a path has no host behind it wherever it
     * lands. Named here rather than left at the default so the assertions say
     * which half of the address they are about.
     */
    private val web = io.mszymanski.orknux.server.security.WebProperties(baseUrl = "https://orknux.example")

    private val mapper = ObjectMapper()

    private val steps = StepPictures(workspaces, web, drawing, pictures, store, settings)
    private val tools = StepPictureTools(mapper, steps)

    @Suppress("UNCHECKED_CAST")
    private fun <T> anyOf(): T = Mockito.any<T>() ?: (null as T)

    private fun workspace(imageModelId: Long? = 5) = Workspace(id = 9, name = "Acme")
        .also { it.imageModelId = imageModelId }

    private fun call(description: String?) = ToolCall(
        id = "call-1",
        name = "draw_picture",
        arguments = if (description == null) "{}" else mapper.writeValueAsString(mapOf("description" to description)),
    )

    private fun drawn() = Picture.Drawn(
        image = byteArrayOf(1, 2, 3),
        contentType = "image/png",
        millis = 40,
    )

    private fun filed(id: Long = 77) = ExecutionPicture(
        id = id,
        executionId = 100,
        nodeKey = "ask",
        workspaceId = 9,
        prompt = "a red bicycle",
        filename = "a-red-bicycle.png",
        contentType = "image/png",
        sizeBytes = 3,
        location = "9/a-red-bicycle.png",
    )

    @Test
    fun `an installation that keeps no attachments is offered no tool`() {
        `when`(settings.attachmentsEnabled()).thenReturn(false)

        assertThat(tools.shed(100, "ask", 9)).isNull()
    }

    @Test
    fun `a workspace that has chosen no image model is offered no tool`() {
        `when`(settings.attachmentsEnabled()).thenReturn(true)
        `when`(workspaces.findById(9)).thenReturn(Optional.of(workspace(imageModelId = null)))

        assertThat(tools.shed(100, "ask", 9)).isNull()
    }

    @Test
    fun `an agent that was not granted pictures is offered no tool`() {
        `when`(settings.attachmentsEnabled()).thenReturn(true)
        `when`(workspaces.findById(9)).thenReturn(Optional.of(workspace()))

        assertThat(tools.shed(100, "ask", 9, granted = false)).isNull()
        // And the installation being able to draw is not enough on its own.
        assertThat(tools.shed(100, "ask", 9, granted = true)).isNotNull()
    }

    @Test
    fun `a workspace that can draw is offered one tool, by name`() {
        `when`(settings.attachmentsEnabled()).thenReturn(true)
        `when`(workspaces.findById(9)).thenReturn(Optional.of(workspace()))

        val shed = requireNotNull(tools.shed(100, "ask", 9))

        assertThat(shed.specs().map { it.name }).containsExactly("draw_picture")
        assertThat(shed.specs().single().parameters.map { it.name }).containsExactly("description")
        assertThat(shed.handles("draw_picture")).isTrue()
        assertThat(shed.handles("chat_draw_picture")).isFalse()
    }

    @Test
    fun `a drawing is filed against this step of this run, and the answer says where it is`() {
        `when`(settings.attachmentsEnabled()).thenReturn(true)
        `when`(workspaces.findById(9)).thenReturn(Optional.of(workspace()))
        `when`(pictures.countByExecutionId(100)).thenReturn(0)
        `when`(drawing.draw(5, "a red bicycle")).thenReturn(drawn())
        `when`(store.put(anyLong(), anyString(), anyOf())).thenReturn("9/a-red-bicycle.png")
        `when`(pictures.save(anyOf<ExecutionPicture>())).thenReturn(filed())

        val answer = mapper.readTree(requireNotNull(tools.shed(100, "ask", 9)).run(call("a red bicycle")))

        assertThat(answer.path("drawn").booleanValue()).isTrue()
        /*
         * Absolute, both of them.
         *
         * What is handed to a model is what a model pastes, and it pastes it
         * wherever it is answering - a Slack thread has no document for a path
         * to be resolved against, so `/api/…` arrived as the markdown
         * construction printed out rather than as a link.
         */
        assertThat(answer.path("url").stringValue())
            .isEqualTo("https://orknux.example/api/execution-pictures/77")
        assertThat(answer.path("markdown").stringValue())
            .isEqualTo("![a red bicycle](https://orknux.example/api/execution-pictures/77)")

        // Against the step that drew it, so the run graph draws it under that
        // node whatever the agent goes on to say.
        val saved = org.mockito.ArgumentCaptor.forClass(ExecutionPicture::class.java)
        verify(pictures).save(saved.capture())
        val row = requireNotNull(saved.value)
        assertThat(row.executionId).isEqualTo(100)
        assertThat(row.nodeKey).isEqualTo("ask")
        assertThat(row.workspaceId).isEqualTo(9)
        assertThat(row.prompt).isEqualTo("a red bicycle")
    }

    /**
     * An installation that has not said where it is still writes a link.
     *
     * The opposite of the rule a mail follows. `IssueNewsMail` writes no link
     * at all on a blank base, because a broken link in somebody's inbox is
     * worse than none - but a picture handed to a model with no host in front
     * of it is not a worse link, it is the markdown printed out in a Slack
     * thread. The development address is a guess; the construction is a bug.
     */
    @Test
    fun `a blank base url still produces a link, not a path`() {
        val unsaid = StepPictures(
            workspaces,
            io.mszymanski.orknux.server.security.WebProperties(baseUrl = ""),
            drawing,
            pictures,
            store,
            settings,
        )

        assertThat(unsaid.urlOf(7)).isEqualTo("http://localhost:5173/api/execution-pictures/7")
    }

    /** A base with a trailing slash does not become a double one. */
    @Test
    fun `a base url is joined once`() {
        val trailing = StepPictures(
            workspaces,
            io.mszymanski.orknux.server.security.WebProperties(baseUrl = "https://orknux.example/"),
            drawing,
            pictures,
            store,
            settings,
        )

        assertThat(trailing.urlOf(7)).isEqualTo("https://orknux.example/api/execution-pictures/7")
    }

    @Test
    fun `a call with nothing to draw is refused in words, and nothing is drawn`() {
        `when`(settings.attachmentsEnabled()).thenReturn(true)
        `when`(workspaces.findById(9)).thenReturn(Optional.of(workspace()))

        val answer = mapper.readTree(requireNotNull(tools.shed(100, "ask", 9)).run(call("   ")))

        assertThat(answer.path("drawn").booleanValue()).isFalse()
        assertThat(answer.path("reason").stringValue()).contains("draw_picture takes a description")
        verify(drawing, never()).draw(anyLong(), anyString())
    }

    @Test
    fun `a provider that refused says so to the agent rather than failing the run`() {
        `when`(settings.attachmentsEnabled()).thenReturn(true)
        `when`(workspaces.findById(9)).thenReturn(Optional.of(workspace()))
        `when`(pictures.countByExecutionId(100)).thenReturn(0)
        `when`(drawing.draw(5, "a red bicycle")).thenReturn(Picture.Failed("The description was rejected."))

        val answer = mapper.readTree(requireNotNull(tools.shed(100, "ask", 9)).run(call("a red bicycle")))

        assertThat(answer.path("drawn").booleanValue()).isFalse()
        assertThat(answer.path("reason").stringValue()).isEqualTo("The description was rejected.")
        verify(pictures, never()).save(anyOf<ExecutionPicture>())
    }

    @Test
    fun `a run that has drawn its fill is refused, and the sentence says how many`() {
        `when`(settings.attachmentsEnabled()).thenReturn(true)
        `when`(workspaces.findById(9)).thenReturn(Optional.of(workspace()))
        `when`(pictures.countByExecutionId(100)).thenReturn(StepPictures.MOST_PICTURES.toLong())

        val answer = mapper.readTree(requireNotNull(tools.shed(100, "ask", 9)).run(call("a red bicycle")))

        assertThat(answer.path("drawn").booleanValue()).isFalse()
        assertThat(answer.path("reason").stringValue()).contains("${StepPictures.MOST_PICTURES} pictures")
        verify(drawing, never()).draw(anyLong(), anyString())
    }

    @Test
    fun `a bracket in the description does not close the alt text early`() {
        `when`(settings.attachmentsEnabled()).thenReturn(true)
        `when`(workspaces.findById(9)).thenReturn(Optional.of(workspace()))
        `when`(pictures.countByExecutionId(100)).thenReturn(0)
        `when`(drawing.draw(anyLong(), anyString())).thenReturn(drawn())
        `when`(store.put(anyLong(), anyString(), anyOf())).thenReturn("9/x.png")
        val awkward = filed().let {
            ExecutionPicture(
                id = 78,
                executionId = 100,
                nodeKey = "ask",
                workspaceId = 9,
                prompt = "a bicycle [red]\nand a hill",
                filename = it.filename,
                contentType = it.contentType,
                sizeBytes = it.sizeBytes,
                location = it.location,
            )
        }
        `when`(pictures.save(anyOf<ExecutionPicture>())).thenReturn(awkward)

        val answer = mapper.readTree(
            requireNotNull(tools.shed(100, "ask", 9)).run(call("a bicycle [red]\nand a hill")),
        )

        assertThat(answer.path("markdown").stringValue())
            .isEqualTo("![a bicycle red and a hill](https://orknux.example/api/execution-pictures/78)")
    }
}
