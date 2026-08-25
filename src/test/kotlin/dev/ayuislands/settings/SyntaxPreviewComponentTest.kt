package dev.ayuislands.settings

import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.FontPreferences
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.ui.EditorTextField
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.syntax.PrimitiveCategory
import dev.ayuislands.syntax.SyntaxIntensityApplicator
import dev.ayuislands.syntax.SyntaxLanguageRegistry
import dev.ayuislands.syntax.SyntaxOverlayLoader
import dev.ayuislands.syntax.SyntaxPreset
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.assertAll
import java.awt.Color
import java.awt.Container
import java.awt.Dimension
import java.awt.image.BufferedImage
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        val component = SyntaxPreviewComponent(AyuVariant.MIRAGE)

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
        val component = SyntaxPreviewComponent(AyuVariant.MIRAGE)
        val editorField = findEditorTextField(component)
        val editor = mockk<EditorEx>(relaxed = true)
        every { editor.colorsScheme } returns currentScheme
        installEditor(editorField, editor)

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

        assertEquals(supported, SyntaxPreviewComponent.catalogLanguagesForTest())
    }

    @Test
    fun `preview categories equal effective unions for every declared language`() {
        val effectiveCategories = effectiveCategoryUnions()
        val categoryAssertions: List<() -> Unit> =
            SyntaxPreviewComponent.catalogLanguagesForTest().map { language ->
                {
                    assertEquals(
                        effectiveCategories[language].orEmpty(),
                        SyntaxPreviewComponent.categoriesForTest(language),
                        "$language preview categories must equal the effective three-variant union.",
                    )
                }
            }

        assertAll(categoryAssertions)
    }

    @Test
    fun `preview resources are unique complete and nonblank`() {
        val languages = SyntaxPreviewComponent.catalogLanguagesForTest()
        val resources = SyntaxPreviewComponent.resourceNamesForTest()

        assertEquals(languages.size, resources.size, "Every declared language must own one unique resource.")
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
                    assertFalse(component.sampleFileNameForTest() == "Preview.txt", language)
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
    fun `preserves language fallbacks without file types`() {
        val unavailableFileType = editorFixture.mockFileType("Unavailable", "txt")
        every { editorFixture.fileTypeManager.getStdFileType(any()) } returns unavailableFileType

        assertEquals(
            "Kotlin",
            SyntaxPreviewComponent.preferredAvailableLanguage(listOf("Java", "Kotlin"), "Kotlin"),
        )
        assertEquals(
            "Java",
            SyntaxPreviewComponent.preferredAvailableLanguage(listOf("Java", "Python"), "Kotlin"),
        )
        assertEquals("", SyntaxPreviewComponent.preferredAvailableLanguage(emptyList(), "Kotlin"))
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

    private fun effectiveCategoryUnions(): Map<String, Set<PrimitiveCategory>> {
        val loader = SyntaxOverlayLoader()
        val categories = linkedMapOf<String, MutableSet<PrimitiveCategory>>()
        val editorBackgrounds =
            mapOf(
                "Mirage" to Color(0x1F, 0x24, 0x30),
                "Dark" to Color(0x0D, 0x10, 0x17),
                "Light" to Color(0xFC, 0xFC, 0xFC),
            )
        for ((variant, background) in editorBackgrounds) {
            val effective =
                SyntaxIntensityApplicator.compute(
                    SyntaxIntensityApplicator.Request(
                        preset = SyntaxPreset.AMBIENT,
                        variantName = variant,
                        editorBg = background,
                        baseline = loader.loadBaselineForVariant(variant),
                        overlay = loader.loadOverlayForVariant(variant),
                    ),
                )
            for ((language, variantCategories) in SyntaxIntensityApplicator.tunableCategories(effective.keys)) {
                categories.getOrPut(language) { linkedSetOf() }.addAll(variantCategories)
            }
        }
        return categories.mapValues { (_, values) -> values.toSet() }
    }
}
