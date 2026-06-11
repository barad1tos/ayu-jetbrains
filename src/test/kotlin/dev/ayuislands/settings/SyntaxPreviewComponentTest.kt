package dev.ayuislands.settings

import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.ui.EditorTextField
import dev.ayuislands.accent.AyuVariant
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.awt.Container
import java.awt.Dimension
import java.awt.image.BufferedImage
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SyntaxPreviewComponentTest {
    private lateinit var kotlinFileType: FileType
    private lateinit var previewProject: Project

    @BeforeTest
    fun setUp() {
        mockkStatic(ApplicationManager::class)
        val appMock = mockk<Application>(relaxed = true)
        every { ApplicationManager.getApplication() } returns appMock

        previewProject = mockk(relaxed = true)
        val projectManager = mockk<ProjectManager>(relaxed = true)
        every { projectManager.defaultProject } returns previewProject
        mockkStatic(ProjectManager::class)
        every { ProjectManager.getInstance() } returns projectManager

        kotlinFileType =
            mockk<FileType>(relaxed = true) {
                every { name } returns "Kotlin"
                every { defaultExtension } returns "kt"
            }
        val fileTypeManager = mockk<FileTypeManager>(relaxed = true)
        every { fileTypeManager.getStdFileType("Kotlin") } returns kotlinFileType

        mockkStatic(FileTypeManager::class)
        every { FileTypeManager.getInstance() } returns fileTypeManager

        val document = mockk<Document>(relaxed = true)
        val editorFactory = mockk<EditorFactory>(relaxed = true)
        every { editorFactory.createDocument(any<String>()) } returns document
        mockkStatic(EditorFactory::class)
        every { EditorFactory.getInstance() } returns editorFactory
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `component embeds a native editor text field for syntax highlighting`() {
        val component = SyntaxPreviewComponent(AyuVariant.MIRAGE)

        val editor = findEditorTextField(component)

        assertNotNull(editor, "Syntax preview must use a native EditorTextField, not hand-painted token text.")
        assertTrue(editor.isViewer, "Syntax preview editor must be read-only.")
        assertEquals(kotlinFileType, editor.fileType, "Syntax preview must request Kotlin syntax highlighting.")
        assertSame(
            previewProject,
            editor.project,
            "Syntax preview editor must receive a Project so EditorTextField installs an EditorHighlighter.",
        )
    }

    @Test
    fun `variant is stored after updatePreview`() {
        val component = SyntaxPreviewComponent(AyuVariant.DARK)

        component.updatePreview(AyuVariant.MIRAGE)

        assertEquals(AyuVariant.MIRAGE, component.variantForTest())
    }

    @Test
    fun `preferred size is non-zero`() {
        val component = SyntaxPreviewComponent(AyuVariant.MIRAGE)
        val preferred = component.preferredSize

        assertTrue(preferred.width > 0, "Preferred width must be positive")
        assertTrue(preferred.height > 0, "Preferred height must be positive")
    }

    @Test
    fun `minimum size is smaller than preferred size`() {
        val component = SyntaxPreviewComponent(AyuVariant.MIRAGE)
        val min = component.minimumSize
        val pref = component.preferredSize

        assertTrue(
            min.width < pref.width,
            "Minimum width (${min.width}) must be less than preferred width (${pref.width})",
        )
    }

    @Test
    fun `paintComponent renders without exception`() {
        val component = SyntaxPreviewComponent(AyuVariant.MIRAGE)
        component.updatePreview(AyuVariant.MIRAGE)
        component.size = Dimension(560, 220)
        component.doLayout()
        val image = BufferedImage(560, 220, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            component.paint(g)
        } finally {
            g.dispose()
        }
    }

    private fun findEditorTextField(container: Container): EditorTextField? =
        container.components.firstNotNullOfOrNull { component ->
            when (component) {
                is EditorTextField -> component
                is Container -> findEditorTextField(component)
                else -> null
            }
        }
}
