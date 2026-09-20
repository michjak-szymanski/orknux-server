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
 */
class StepPictureToolsTest {

    private val workspaces = mock(WorkspaceRepository::class.java)
    private val drawing = mock(ModelImageClient::class.java)
    private val pictures = mock(ExecutionPictureRepository::class.java)
    private val store = mock(AttachmentStore::class.java)
    private val settings = mock(InstallationSettings::class.java)
    private val mapper = ObjectMapper()

    private val steps = StepPictures(workspaces, drawing, pictures, store, settings)
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
        assertThat(answer.path("url").stringValue()).isEqualTo("/api/execution-pictures/77")
        assertThat(answer.path("markdown").stringValue()).isEqualTo("![a red bicycle](/api/execution-pictures/77)")

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
            .isEqualTo("![a bicycle red and a hill](/api/execution-pictures/78)")
    }
}
