package dev.ayuislands.settings

import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.FontPreferences
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighter
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorTextField
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.syntax.PrimitiveCategory
import dev.ayuislands.syntax.SyntaxIntensityApplicator
import dev.ayuislands.syntax.SyntaxLanguageRegistry
import dev.ayuislands.syntax.SyntaxOverlayLoader
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.assertAll
import java.awt.Container
import java.awt.Dimension
import java.awt.image.BufferedImage
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SyntaxPreviewComponentTest {
    private lateinit var editorFixture: SyntaxPreviewEditorFixture

    @BeforeTest
    fun setUp() {
        mockkStatic(ApplicationManager::class)
        val appMock = mockk<Application>(relaxed = true)
        every { ApplicationManager.getApplication() } returns appMock

        editorFixture = SyntaxPreviewEditorFixture()
        editorFixture.install()
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `component embeds a native editor text field for syntax highlighting`() {
        val previewFile = mockk<VirtualFile>(relaxed = true)
        val component = SyntaxPreviewComponent(AyuVariant.MIRAGE) { _, _, _ -> previewFile }

        val editor = findEditorTextField(component)

        assertNotNull(editor, "Syntax preview must use a native EditorTextField, not hand-painted token text.")
        assertTrue(editor.isViewer, "Syntax preview editor must be read-only.")
        assertEquals(
            editorFixture.kotlinFileType,
            editor.fileType,
            "Syntax preview must request Kotlin syntax highlighting.",
        )
        assertEquals("Kotlin", component.languageForTest(), "Syntax preview must default to the Kotlin sample.")
        assertSame(
            editorFixture.previewProject,
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
    fun `updatePreview refreshes the existing native editor color scheme`() {
        val colorsManager = mockk<EditorColorsManager>()
        val currentScheme = mockk<EditorColorsScheme>()
        val globalScheme = mockk<EditorColorsScheme>(relaxed = true)
        val previewScheme = mockk<EditorColorsScheme>(relaxed = true)
        val fontPreferences = mockk<FontPreferences>(relaxed = true)
        mockkStatic(EditorColorsManager::class)
        every { EditorColorsManager.getInstance() } returns colorsManager
        every { colorsManager.globalScheme } returns globalScheme
        every { globalScheme.clone() } returns previewScheme
        every { currentScheme.fontPreferences } returns fontPreferences
        every { currentScheme.editorFontName } returns "Preview Font"
        every { currentScheme.editorFontSize } returns 12
        every { currentScheme.lineSpacing } returns 1.1f
        every { currentScheme.isUseLigatures } returns true
        val previewFile = mockk<VirtualFile>(relaxed = true)
        val component = SyntaxPreviewComponent(AyuVariant.MIRAGE) { _, _, _ -> previewFile }
        val editorField = findEditorTextField(component)
        val editor = mockk<EditorEx>(relaxed = true)
        every { editor.colorsScheme } returns currentScheme
        installEditor(editorField, editor)
        val highlighterFactory = mockk<EditorHighlighterFactory>()
        every {
            ApplicationManager.getApplication().getService(EditorHighlighterFactory::class.java)
        } returns highlighterFactory
        every {
            highlighterFactory.createEditorHighlighter(any<VirtualFile>(), previewScheme, editorFixture.previewProject)
        } returns mockk(relaxed = true)

        component.updatePreview(AyuVariant.MIRAGE)

        verify(exactly = 1) { previewScheme.fontPreferences = fontPreferences }
        verify(exactly = 1) { previewScheme.editorFontName = "Preview Font" }
        verify(exactly = 1) { previewScheme.editorFontSize = 12 }
        verify(exactly = 1) { previewScheme.lineSpacing = 1.1f }
        verify(exactly = 1) { previewScheme.isUseLigatures = true }
        verify(exactly = 1) { editor.colorsScheme = previewScheme }
        verify(exactly = 0) { editor.reinitSettings() }
    }

    @Test
    fun `updatePreview switches the native editor file type when language changes`() {
        val component = SyntaxPreviewComponent(AyuVariant.MIRAGE)

        component.updatePreview(AyuVariant.MIRAGE, "Java")

        val editor = findEditorTextField(component)
        assertNotNull(editor, "Syntax preview must keep the native editor when switching languages.")
        assertEquals("Java", component.languageForTest(), "Syntax preview must track the selected language.")
        assertEquals(editorFixture.javaFileType, editor.fileType, "Java tuning must render through the Java file type.")
    }

    @Test
    fun `primitive navigation reads each bundled sample at most once per component`() {
        var loadCount = 0
        val component =
            SyntaxPreviewComponent(
                variant = AyuVariant.MIRAGE,
                previewCodeLoader = {
                    loadCount += 1
                    "fun cachedPreview() = 42"
                },
            )

        component.showPrimitive(PrimitiveCategory.FUNCTION_DECL)
        component.showPrimitive(PrimitiveCategory.FUNCTION_DECL)

        assertEquals(1, loadCount)
    }

    @Test
    fun `Swift preview uses the registered file association when the standard name is unavailable`() {
        val noctuleSwift = editorFixture.mockFileType("NoctuleSwift", "swift")
        every { editorFixture.fileTypeManager.getStdFileType("Swift") } returns
            editorFixture.mockFileType("NotSwift", "txt")
        every { editorFixture.fileTypeManager.getFileTypeByFileName("Preview.swift") } returns noctuleSwift

        val component = SyntaxPreviewComponent(AyuVariant.MIRAGE, "Swift")

        val editor = assertNotNull(findEditorTextField(component))
        assertSame(noctuleSwift, editor.fileType)
        assertEquals("Syntax color preview", component.toolTipText)
    }

    @Test
    fun `updatePreview installs a virtual-file highlighter for the selected language`() {
        val swiftFileType = editorFixture.mockFileType("Swift", "swift")
        every { editorFixture.fileTypeManager.getStdFileType("Swift") } returns swiftFileType
        val colorsManager = mockk<EditorColorsManager>()
        val currentScheme = mockk<EditorColorsScheme>(relaxed = true)
        val globalScheme = mockk<EditorColorsScheme>(relaxed = true)
        val previewScheme = mockk<EditorColorsScheme>(relaxed = true)
        val fontPreferences = mockk<FontPreferences>(relaxed = true)
        every { currentScheme.fontPreferences } returns fontPreferences
        every { currentScheme.editorFontName } returns "Preview Font"
        every { currentScheme.editorFontSize } returns 12
        every { currentScheme.lineSpacing } returns 1.0f
        every { currentScheme.isUseLigatures } returns false
        every { colorsManager.globalScheme } returns globalScheme
        every { globalScheme.clone() } returns previewScheme
        mockkStatic(EditorColorsManager::class)
        every { EditorColorsManager.getInstance() } returns colorsManager
        val highlighterFactory = mockk<EditorHighlighterFactory>()
        val highlighter = mockk<EditorHighlighter>(relaxed = true)
        every {
            ApplicationManager.getApplication().getService(EditorHighlighterFactory::class.java)
        } returns highlighterFactory
        every {
            highlighterFactory.createEditorHighlighter(any<VirtualFile>(), previewScheme, editorFixture.previewProject)
        } returns highlighter
        val previewFile = mockk<VirtualFile>()
        every { previewFile.name } returns "Preview.swift"
        every { previewFile.fileType } returns swiftFileType
        val component =
            SyntaxPreviewComponent(AyuVariant.MIRAGE, "Swift") { name, fileType, _ ->
                assertEquals("Preview.swift", name)
                assertSame(swiftFileType, fileType)
                previewFile
            }
        val editorField = findEditorTextField(component)
        val editor = mockk<EditorEx>(relaxed = true)
        every { editor.colorsScheme } returns currentScheme
        installEditor(editorField, editor)

        component.updatePreview(AyuVariant.MIRAGE, "Swift")

        verify(exactly = 1) {
            highlighterFactory.createEditorHighlighter(
                previewFile,
                previewScheme,
                editorFixture.previewProject,
            )
        }
        verify(exactly = 1) { editor.highlighter = highlighter }
    }

    @Test
    fun `native highlighter failure replaces stale highlighting with plain text`() {
        val colorsManager = mockk<EditorColorsManager>()
        val currentScheme = mockk<EditorColorsScheme>(relaxed = true)
        val globalScheme = mockk<EditorColorsScheme>(relaxed = true)
        val previewScheme = mockk<EditorColorsScheme>(relaxed = true)
        every { currentScheme.fontPreferences } returns mockk(relaxed = true)
        every { currentScheme.editorFontName } returns "Preview Font"
        every { currentScheme.editorFontSize } returns 12
        every { currentScheme.lineSpacing } returns 1.0f
        every { currentScheme.isUseLigatures } returns false
        every { colorsManager.globalScheme } returns globalScheme
        every { globalScheme.clone() } returns previewScheme
        mockkStatic(EditorColorsManager::class)
        every { EditorColorsManager.getInstance() } returns colorsManager
        val highlighterFactory = mockk<EditorHighlighterFactory>()
        val plainHighlighter = mockk<EditorHighlighter>(relaxed = true)
        every {
            ApplicationManager.getApplication().getService(EditorHighlighterFactory::class.java)
        } returns highlighterFactory
        val previewFile = mockk<VirtualFile>(relaxed = true)
        every {
            highlighterFactory.createEditorHighlighter(previewFile, previewScheme, editorFixture.previewProject)
        } throws RuntimeException("native highlighter failed")
        every {
            highlighterFactory.createEditorHighlighter(
                PlainTextFileType.INSTANCE,
                previewScheme,
                editorFixture.previewProject,
            )
        } returns plainHighlighter
        val component = SyntaxPreviewComponent(AyuVariant.MIRAGE) { _, _, _ -> previewFile }
        val editorField = findEditorTextField(component)
        val editor = mockk<EditorEx>(relaxed = true)
        every { editor.colorsScheme } returns currentScheme
        installEditor(editorField, editor)

        component.updatePreview(AyuVariant.MIRAGE)

        verify(exactly = 1) { editor.highlighter = plainHighlighter }
    }

    @Test
    fun `highlighter factory lookup failure leaves settings usable`() {
        val colorsManager = mockk<EditorColorsManager>()
        val currentScheme = mockk<EditorColorsScheme>(relaxed = true)
        val globalScheme = mockk<EditorColorsScheme>(relaxed = true)
        val previewScheme = mockk<EditorColorsScheme>(relaxed = true)
        every { currentScheme.fontPreferences } returns mockk(relaxed = true)
        every { currentScheme.editorFontName } returns "Preview Font"
        every { currentScheme.editorFontSize } returns 12
        every { currentScheme.lineSpacing } returns 1.0f
        every { currentScheme.isUseLigatures } returns false
        every { colorsManager.globalScheme } returns globalScheme
        every { globalScheme.clone() } returns previewScheme
        mockkStatic(EditorColorsManager::class)
        every { EditorColorsManager.getInstance() } returns colorsManager
        every {
            ApplicationManager.getApplication().getService(EditorHighlighterFactory::class.java)
        } throws RuntimeException("highlighter service unavailable")
        val previewFile = mockk<VirtualFile>(relaxed = true)
        val component = SyntaxPreviewComponent(AyuVariant.MIRAGE) { _, _, _ -> previewFile }
        val editorField = findEditorTextField(component)
        val editor = mockk<EditorEx>(relaxed = true)
        every { editor.colorsScheme } returns currentScheme
        installEditor(editorField, editor)

        component.updatePreview(AyuVariant.MIRAGE)

        verify(exactly = 0) { editor.highlighter = any() }
    }

    @Test
    fun `highlighter factory lookup propagates platform cancellation`() {
        val colorsManager = mockk<EditorColorsManager>()
        val currentScheme = mockk<EditorColorsScheme>(relaxed = true)
        val globalScheme = mockk<EditorColorsScheme>(relaxed = true)
        val previewScheme = mockk<EditorColorsScheme>(relaxed = true)
        every { currentScheme.fontPreferences } returns mockk(relaxed = true)
        every { currentScheme.editorFontName } returns "Preview Font"
        every { currentScheme.editorFontSize } returns 12
        every { currentScheme.lineSpacing } returns 1.0f
        every { currentScheme.isUseLigatures } returns false
        every { colorsManager.globalScheme } returns globalScheme
        every { globalScheme.clone() } returns previewScheme
        mockkStatic(EditorColorsManager::class)
        every { EditorColorsManager.getInstance() } returns colorsManager
        every {
            ApplicationManager.getApplication().getService(EditorHighlighterFactory::class.java)
        } throws ProcessCanceledException()
        val previewFile = mockk<VirtualFile>(relaxed = true)
        val component = SyntaxPreviewComponent(AyuVariant.MIRAGE) { _, _, _ -> previewFile }
        val editorField = findEditorTextField(component)
        val editor = mockk<EditorEx>(relaxed = true)
        every { editor.colorsScheme } returns currentScheme
        installEditor(editorField, editor)

        kotlin.test.assertFailsWith<ProcessCanceledException> {
            component.updatePreview(AyuVariant.MIRAGE)
        }
    }

    @Test
    fun `factory and native failures keep independent log latches`() {
        val colorsManager = mockk<EditorColorsManager>()
        val currentScheme = mockk<EditorColorsScheme>(relaxed = true)
        val globalScheme = mockk<EditorColorsScheme>(relaxed = true)
        val previewScheme = mockk<EditorColorsScheme>(relaxed = true)
        every { currentScheme.fontPreferences } returns mockk(relaxed = true)
        every { currentScheme.editorFontName } returns "Preview Font"
        every { currentScheme.editorFontSize } returns 12
        every { currentScheme.lineSpacing } returns 1.0f
        every { currentScheme.isUseLigatures } returns false
        every { colorsManager.globalScheme } returns globalScheme
        every { globalScheme.clone() } returns previewScheme
        mockkStatic(EditorColorsManager::class)
        every { EditorColorsManager.getInstance() } returns colorsManager
        val highlighterFactory = mockk<EditorHighlighterFactory>()
        every {
            ApplicationManager.getApplication().getService(EditorHighlighterFactory::class.java)
        } throws RuntimeException("highlighter service unavailable") andThen highlighterFactory
        val previewFile = mockk<VirtualFile>(relaxed = true)
        every {
            highlighterFactory.createEditorHighlighter(previewFile, previewScheme, editorFixture.previewProject)
        } throws RuntimeException("native highlighter failed")
        every {
            highlighterFactory.createEditorHighlighter(
                PlainTextFileType.INSTANCE,
                previewScheme,
                editorFixture.previewProject,
            )
        } returns mockk(relaxed = true)
        val component = SyntaxPreviewComponent(AyuVariant.MIRAGE) { _, _, _ -> previewFile }
        val editorField = findEditorTextField(component)
        val editor = mockk<EditorEx>(relaxed = true)
        every { editor.colorsScheme } returns currentScheme
        installEditor(editorField, editor)

        component.updatePreview(AyuVariant.MIRAGE)
        component.updatePreview(AyuVariant.MIRAGE)

        val factoryFailures = SyntaxPreviewComponent::class.java.getDeclaredField("failedFactoryLanguages")
        factoryFailures.isAccessible = true
        val nativeFailures = SyntaxPreviewComponent::class.java.getDeclaredField("failedNativeLanguages")
        nativeFailures.isAccessible = true
        assertEquals(setOf("Kotlin"), factoryFailures.get(component))
        assertEquals(setOf("Kotlin"), nativeFailures.get(component))
    }

    @Test
    fun `native highlighter creation propagates platform cancellation`() {
        val colorsManager = mockk<EditorColorsManager>()
        val currentScheme = mockk<EditorColorsScheme>(relaxed = true)
        val globalScheme = mockk<EditorColorsScheme>(relaxed = true)
        val previewScheme = mockk<EditorColorsScheme>(relaxed = true)
        every { currentScheme.fontPreferences } returns mockk(relaxed = true)
        every { currentScheme.editorFontName } returns "Preview Font"
        every { currentScheme.editorFontSize } returns 12
        every { currentScheme.lineSpacing } returns 1.0f
        every { currentScheme.isUseLigatures } returns false
        every { colorsManager.globalScheme } returns globalScheme
        every { globalScheme.clone() } returns previewScheme
        mockkStatic(EditorColorsManager::class)
        every { EditorColorsManager.getInstance() } returns colorsManager
        val highlighterFactory = mockk<EditorHighlighterFactory>()
        every {
            ApplicationManager.getApplication().getService(EditorHighlighterFactory::class.java)
        } returns highlighterFactory
        val previewFile = mockk<VirtualFile>(relaxed = true)
        every {
            highlighterFactory.createEditorHighlighter(previewFile, previewScheme, editorFixture.previewProject)
        } throws ProcessCanceledException()
        val component = SyntaxPreviewComponent(AyuVariant.MIRAGE) { _, _, _ -> previewFile }
        val editorField = findEditorTextField(component)
        val editor = mockk<EditorEx>(relaxed = true)
        every { editor.colorsScheme } returns currentScheme
        installEditor(editorField, editor)

        kotlin.test.assertFailsWith<ProcessCanceledException> {
            component.updatePreview(AyuVariant.MIRAGE)
        }
    }

    @Test
    fun `editor lifecycle installs the virtual-file highlighter before user interaction`() {
        val colorsManager = mockk<EditorColorsManager>()
        val currentScheme = mockk<EditorColorsScheme>(relaxed = true)
        val globalScheme = mockk<EditorColorsScheme>(relaxed = true)
        val previewScheme = mockk<EditorColorsScheme>(relaxed = true)
        every { colorsManager.globalScheme } returns globalScheme
        every { globalScheme.clone() } returns previewScheme
        mockkStatic(EditorColorsManager::class)
        every { EditorColorsManager.getInstance() } returns colorsManager
        val highlighterFactory = mockk<EditorHighlighterFactory>()
        val highlighter = mockk<EditorHighlighter>(relaxed = true)
        every {
            ApplicationManager.getApplication().getService(EditorHighlighterFactory::class.java)
        } returns highlighterFactory
        val previewFile = mockk<VirtualFile>(relaxed = true)
        every {
            highlighterFactory.createEditorHighlighter(previewFile, previewScheme, editorFixture.previewProject)
        } returns highlighter
        val component = SyntaxPreviewComponent(AyuVariant.MIRAGE) { _, _, _ -> previewFile }
        val editorField = assertNotNull(findEditorTextField(component))
        val editor = mockk<EditorEx>(relaxed = true)
        every { editor.colorsScheme } returns currentScheme
        val onEditorAdded = editorField.javaClass.getDeclaredMethod("onEditorAdded", Editor::class.java)
        onEditorAdded.isAccessible = true

        onEditorAdded.invoke(editorField, editor)

        verify(exactly = 1) {
            highlighterFactory.createEditorHighlighter(previewFile, previewScheme, editorFixture.previewProject)
        }
        verify(exactly = 1) { editor.highlighter = highlighter }
    }

    @Test
    fun `updatePreview uses curated sample for non-Kotlin languages`() {
        val pythonFileType = editorFixture.mockFileType("Python", "py")
        every { editorFixture.fileTypeManager.getStdFileType("Python") } returns pythonFileType
        val component = SyntaxPreviewComponent(AyuVariant.MIRAGE)

        component.updatePreview(AyuVariant.MIRAGE, "Python")

        val editor = findEditorTextField(component)
        assertNotNull(editor, "Syntax preview must keep the native editor when switching to Python.")
        assertEquals("Python", component.languageForTest(), "Syntax preview must track the selected language.")
        assertEquals(
            "preset_preview.py",
            component.sampleFileNameForTest(),
            "Python preview must use its curated sample.",
        )
        assertTrue(
            component.sampleCodeForTest().contains("def render"),
            "Python preview must load the curated resource code.",
        )
        assertEquals(pythonFileType, editor.fileType, "Python tuning must render through the Python file type.")
    }

    @Test
    fun `core catalog includes Swift with a native representative sample`() {
        val expected =
            setOf(
                "CSS",
                "Go",
                "HTML",
                "Java",
                "JavaScript",
                "JSON",
                "Kotlin",
                "Markdown",
                "Python",
                "Rust",
                "Swift",
                "TypeScript",
                "YAML",
            )
        assertTrue(SyntaxPreviewComponent.catalogLanguagesForTest().containsAll(expected))

        val component = SyntaxPreviewComponent(AyuVariant.MIRAGE)
        component.updatePreview(AyuVariant.MIRAGE, "Swift")

        assertEquals("Preview.swift", component.sampleFileNameForTest())
        assertTrue(component.sampleCodeForTest().contains("protocol Previewing"))
        assertTrue(component.sampleCodeForTest().contains("final class Preview<Value>"))
        assertTrue(component.sampleCodeForTest().contains("@MainActor"))
        assertTrue(component.sampleCodeForTest().contains("static let"))
        assertTrue(component.sampleCodeForTest().contains("///"))
    }

    @Test
    fun `preview catalog exactly covers every declared syntax language`() {
        val supported = SyntaxLanguageRegistry.supportedLanguages().mapTo(linkedSetOf()) { it.displayName }

        assertEquals(
            expected = supported,
            actual = SyntaxPreviewComponent.catalogLanguagesForTest(),
        )
    }

    @Test
    fun `preview category claims are mapped identically by every Ayu variant`() {
        // Native ownership and actuation are verified separately at the IntelliJ fixture boundary.
        val categoriesByVariant = effectiveCategoriesByVariant()
        val categoryAssertions: List<() -> Unit> =
            SyntaxPreviewComponent.catalogLanguagesForTest().map { language ->
                {
                    val declared = SyntaxPreviewComponent.categoriesForTest(language)
                    val variantCategories = categoriesByVariant.mapValues { (_, values) -> values[language].orEmpty() }
                    assertEquals(
                        1,
                        variantCategories.values.distinct().size,
                        "$language mappings must not drift between Ayu variants: $variantCategories",
                    )
                    assertTrue(
                        variantCategories.values.all { available -> available.containsAll(declared) },
                        "$language preview claims must be mapped in every Ayu variant: " +
                            "declared=$declared, variants=$variantCategories",
                    )
                }
            }

        assertAll(categoryAssertions)
    }

    @Test
    fun `preview resources are unique complete and nonblank`() {
        val specifications = SyntaxLanguageRegistry.specifications()
        val resources = specifications.flatMap { it.preview.files }.map { it.resourceName }

        assertTrue(specifications.all { it.preview.files.isNotEmpty() }, "Every declared language must own a resource.")
        assertEquals(resources.size, resources.toSet().size, "Every preview surface must own one unique resource.")
        resources.forEach { resource ->
            val path = "/dev/ayuislands/settings/syntax-preview/$resource"
            val url = assertNotNull(SyntaxPreviewComponent::class.java.getResource(path), path)
            assertTrue(url.readText().isNotBlank(), "$path must be nonblank.")
        }
    }

    @Test
    fun `every declared language preserves its native sample through fallback`() {
        val component = SyntaxPreviewComponent(AyuVariant.MIRAGE)
        val sampleAssertions: List<() -> Unit> =
            SyntaxPreviewComponent.catalogLanguagesForTest().map { language ->
                {
                    component.updatePreview(AyuVariant.MIRAGE, language)
                    assertNotEquals("Preview.txt", component.sampleFileNameForTest(), language)
                    assertFalse(
                        component.sampleCodeForTest().contains("class Preview {\n    value = \"hello\""),
                        language,
                    )
                }
            }

        assertAll(sampleAssertions)
    }

    @Test
    fun `component falls back to plain text when standard file type mismatches the sample`() {
        every { editorFixture.fileTypeManager.getStdFileType("Swift") } returns
            editorFixture.mockFileType("NotSwift", "txt")

        val component = SyntaxPreviewComponent(AyuVariant.MIRAGE)
        component.updatePreview(AyuVariant.MIRAGE, "Swift")

        val editor = findEditorTextField(component)
        assertNotNull(editor, "Syntax preview must still build when the expected file type is unavailable.")
        assertSame(PlainTextFileType.INSTANCE, editor.fileType)
        assertEquals("Preview.swift", component.sampleFileNameForTest())
        assertTrue(component.sampleCodeForTest().contains("protocol Previewing"))
        assertTrue(component.toolTipText.contains("Swift"))
        assertTrue(component.toolTipText.contains("this IDE"))
        assertFalse(component.sampleCodeForTest().contains("// tune syntax colors"))
    }

    @Test
    fun `component falls back to plain text when standard file type lookup fails`() {
        every { editorFixture.fileTypeManager.getStdFileType("Swift") } throws
            RuntimeException("missing Swift plugin")

        val component = SyntaxPreviewComponent(AyuVariant.MIRAGE)
        component.updatePreview(AyuVariant.MIRAGE, "Swift")

        val editor = findEditorTextField(component)
        assertNotNull(editor, "Syntax preview must still build when file type lookup throws.")
        assertSame(PlainTextFileType.INSTANCE, editor.fileType)
        assertEquals("Preview.swift", component.sampleFileNameForTest())
        assertTrue(component.sampleCodeForTest().contains("protocol Previewing"))
        assertTrue(component.toolTipText.contains("Swift"))
        assertTrue(component.toolTipText.contains("this IDE"))
        assertFalse(component.sampleCodeForTest().contains("// tune syntax colors"))
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

    private fun installEditor(
        editorField: EditorTextField?,
        editor: EditorEx,
    ) {
        assertNotNull(editorField, "Syntax preview must contain an EditorTextField.")
        val editorFieldBackingField = EditorTextField::class.java.getDeclaredField("myEditor")
        editorFieldBackingField.isAccessible = true
        editorFieldBackingField.set(editorField, editor)
    }

    private fun effectiveCategoriesByVariant(): Map<String, Map<String, Set<PrimitiveCategory>>> {
        val loader = SyntaxOverlayLoader()
        return listOf("Mirage", "Dark", "Light").associateWith { variant ->
            val baseline = loader.loadBaselineForVariant(variant)
            val overlay = loader.loadOverlayForVariant(variant)
            SyntaxIntensityApplicator.tunableCategories(
                baseline = baseline,
                overlay = overlay,
                fallbacks = loader.fallbacksFor(variant),
            )
        }
    }
}
