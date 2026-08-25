package dev.ayuislands.settings

import com.intellij.lang.Language
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.Disposer
import com.intellij.ui.EditorTextField
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.syntax.PrimitiveCategory
import org.jetbrains.annotations.TestOnly
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.util.Locale
import javax.swing.JComponent

/**
 * Live preview of syntax-intensity colors rendered as a compact IDE scene.
 *
 * The project tree is a lightweight frame, while the code pane is a native
 * [EditorTextField] so syntax colors come from the active IntelliJ editor
 * highlighter and color scheme instead of a hand-painted token imitation.
 */
internal class SyntaxPreviewComponent(
    private var variant: AyuVariant,
    private var language: String = DEFAULT_LANGUAGE,
) : JComponent(),
    Disposable {
    private var previewSample: PreviewSample = sampleFor(language)
    private var fileTypeResolution: PreviewFileType = resolvePreviewFileType(language, previewSample)
    private val editorField: EditorTextField = createEditorField()
    private var isDisposed = false

    init {
        layout = null
        isOpaque = false
        refreshFallbackTooltip(normalizeLanguage(language), fileTypeResolution)
        add(editorField)
    }

    fun updatePreview(
        variant: AyuVariant,
        language: String = this.language,
    ) {
        this.variant = variant
        val nextLanguage = normalizeLanguage(language)
        val nextSample = sampleFor(nextLanguage)
        if (nextLanguage != this.language || nextSample != previewSample) {
            this.language = nextLanguage
            previewSample = nextSample
            fileTypeResolution = resolvePreviewFileType(nextLanguage, nextSample)
            val document = EditorFactory.getInstance().createDocument(nextSample.code)
            editorField.setNewDocumentAndFileType(fileTypeResolution.fileType, document)
            refreshFallbackTooltip(nextLanguage, fileTypeResolution)
        }
        refreshEditorColorsScheme()
        editorField.background = editorSurface()
        editorField.repaint()
        revalidate()
        repaint()
    }

    private fun refreshEditorColorsScheme() {
        val editor = editorField.getEditor(false) ?: return
        editor.colorsScheme = previewColorsScheme(editor.colorsScheme)
    }

    private fun previewColorsScheme(currentScheme: EditorColorsScheme): EditorColorsScheme {
        val previewScheme = EditorColorsManager.getInstance().globalScheme.clone() as EditorColorsScheme
        previewScheme.fontPreferences = currentScheme.fontPreferences
        previewScheme.editorFontName = currentScheme.editorFontName
        previewScheme.editorFontSize = currentScheme.editorFontSize
        previewScheme.lineSpacing = currentScheme.lineSpacing
        previewScheme.isUseLigatures = currentScheme.isUseLigatures
        return previewScheme
    }

    override fun getPreferredSize(): Dimension = Dimension(JBUI.scale(PREVIEW_WIDTH), JBUI.scale(PREVIEW_HEIGHT))

    override fun getMinimumSize(): Dimension = Dimension(JBUI.scale(MIN_PREVIEW_WIDTH), JBUI.scale(PREVIEW_HEIGHT))

    override fun doLayout() {
        val layout = PreviewChromePainter.layout(width, height)
        editorField.setBounds(
            layout.editorX + JBUI.scale(EDITOR_INSET),
            layout.padding + JBUI.scale(EDITOR_INSET),
            (layout.editorWidth - JBUI.scale(EDITOR_INSET * 2)).coerceAtLeast(1),
            (layout.contentHeight - JBUI.scale(EDITOR_INSET * 2)).coerceAtLeast(1),
        )
    }

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        val g2 = graphics.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

            PreviewChromePainter.paintOuterPanel(g2, width, height, editorSurface())

            val layout = PreviewChromePainter.layout(width, height)
            paintProjectPanel(g2, layout.padding, layout.padding, layout.projectWidth, layout.contentHeight)
            paintEditorPanelFrame(g2, layout.editorX, layout.padding, layout.editorWidth, layout.contentHeight)
        } finally {
            g2.dispose()
        }
    }

    override fun removeNotify() {
        super.removeNotify()
        if (!isDisposed) {
            Disposer.dispose(this)
        }
    }

    override fun dispose() {
        isDisposed = true
    }

    private fun createEditorField(): EditorTextField =
        EditorTextField(
            previewSample.code,
            ProjectManager.getInstance().defaultProject,
            fileTypeResolution.fileType,
        ).apply {
            isViewer = true
            setDisposedWith(this@SyntaxPreviewComponent)
            background = editorSurface()
            isOpaque = false
        }

    private fun paintProjectPanel(
        g2: Graphics2D,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ) {
        PreviewChromePainter.paintProjectPanel(
            g2 = g2,
            panel =
                PreviewChromeProjectPanel(
                    bounds = Rectangle(x, y, width, height),
                    surface = panelSurface(),
                    rows = projectRows(),
                    markerShape = PreviewChromeMarkerShape.ROUND,
                    textColor = UIUtil.getLabelForeground(),
                ),
        )
    }

    private fun paintEditorPanelFrame(
        g2: Graphics2D,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ) {
        PreviewChromePainter.paintPanelFrame(g2, Rectangle(x, y, width, height), editorSurface())
    }

    private fun projectRows(): List<PreviewChromeProjectRow> =
        listOf(PreviewChromeProjectRow(LANGUAGE_FILE_DOT, previewSample.fileName)) + PROJECT_ROW_TAIL

    private fun refreshFallbackTooltip(
        language: String,
        resolution: PreviewFileType,
    ) {
        toolTipText =
            if (resolution.isPlainTextFallback) {
                "Syntax highlighting for $language is unavailable in this IDE; " +
                    "showing the native sample as plain text."
            } else {
                "Syntax color preview"
            }
    }

    private fun editorSurface(): Color = surfacePalette().editor

    private fun panelSurface(): Color = surfacePalette().panel

    private fun surfacePalette(): SurfacePalette =
        when (variant) {
            AyuVariant.DARK -> DARK_PALETTE
            AyuVariant.MIRAGE -> MIRAGE_PALETTE
            AyuVariant.LIGHT -> LIGHT_PALETTE
        }

    @TestOnly
    internal fun variantForTest(): AyuVariant = variant

    @TestOnly
    internal fun languageForTest(): String = language

    @TestOnly
    internal fun sampleFileNameForTest(): String = previewSample.fileName

    @TestOnly
    internal fun sampleCodeForTest(): String = previewSample.code

    private data class SurfacePalette(
        val editor: Color,
        val panel: Color,
    )

    private data class PreviewSample(
        val fileName: String,
        val standardFileTypeName: String?,
        val defaultExtension: String?,
        val code: String,
    )

    private data class PreviewFileType(
        val fileType: FileType,
        val isPlainTextFallback: Boolean,
    )

    private data class PreviewSampleSpec(
        val language: String,
        val fileName: String,
        val standardFileTypeName: String,
        val defaultExtension: String,
        val resourceName: String,
        val demonstratedCategories: Set<PrimitiveCategory>,
    )

    internal companion object {
        private val LOG = logger<SyntaxPreviewComponent>()

        private const val PREVIEW_WIDTH = 560
        private const val MIN_PREVIEW_WIDTH = 320
        private const val PREVIEW_HEIGHT = 220
        private const val EDITOR_INSET = 1
        private const val DEFAULT_LANGUAGE = "Kotlin"

        private val DARK_PALETTE = SurfacePalette(fixedColor(0x0D1017), fixedColor(0x141923))
        private val MIRAGE_PALETTE = SurfacePalette(fixedColor(0x1F2430), fixedColor(0x252B38))
        private val LIGHT_PALETTE = SurfacePalette(fixedColor(0xFAFAFA), fixedColor(0xEFF2F5))
        private val LANGUAGE_FILE_DOT = fixedColor(0x59C2FF)
        private val PROJECT_ROW_TAIL =
            listOf(
                PreviewChromeProjectRow(fixedColor(0x7FD17F), "Config.java"),
                PreviewChromeProjectRow(fixedColor(0xFFA759), "Types.kt"),
                PreviewChromeProjectRow(fixedColor(0xFFD580), "build/"),
            )

        private val PREVIEW_SAMPLE_SPECS =
            listOf(
                PreviewSampleSpec(
                    "Kotlin",
                    "PresetPreview.kt",
                    "Kotlin",
                    "kt",
                    "kotlin.txt",
                    categorySet(
                        PrimitiveCategory.COMMENT,
                        PrimitiveCategory.DOCUMENTATION,
                        PrimitiveCategory.STRING_LITERAL,
                        PrimitiveCategory.CLASS_DECL,
                        PrimitiveCategory.ANNOTATION,
                        PrimitiveCategory.LOCAL_VAR,
                        PrimitiveCategory.FUNCTION_DECL,
                        PrimitiveCategory.INSTANCE_FIELD,
                        PrimitiveCategory.INTERFACE_DECL,
                        PrimitiveCategory.KEYWORD,
                        PrimitiveCategory.PARAMETER,
                        PrimitiveCategory.NUMBER_LITERAL,
                        PrimitiveCategory.TYPE_REF,
                    ),
                ),
                PreviewSampleSpec(
                    "Java",
                    "PresetPreview.java",
                    "JAVA",
                    "java",
                    "java.txt",
                    categorySet(
                        PrimitiveCategory.COMMENT,
                        PrimitiveCategory.DOCUMENTATION,
                        PrimitiveCategory.CLASS_DECL,
                        PrimitiveCategory.ANNOTATION,
                        PrimitiveCategory.OPERATOR,
                        PrimitiveCategory.FUNCTION_DECL,
                        PrimitiveCategory.PARAMETER,
                        PrimitiveCategory.INSTANCE_FIELD,
                        PrimitiveCategory.INTERFACE_DECL,
                        PrimitiveCategory.STRING_LITERAL,
                        PrimitiveCategory.KEYWORD,
                        PrimitiveCategory.LOCAL_VAR,
                        PrimitiveCategory.NUMBER_LITERAL,
                        PrimitiveCategory.STATIC_FIELD,
                    ),
                ),
                PreviewSampleSpec(
                    "Python",
                    "preset_preview.py",
                    "Python",
                    "py",
                    "python.txt",
                    categorySet(
                        PrimitiveCategory.ANNOTATION,
                        PrimitiveCategory.OPERATOR,
                        PrimitiveCategory.DOCUMENTATION,
                        PrimitiveCategory.STRING_LITERAL,
                        PrimitiveCategory.FUNCTION_DECL,
                        PrimitiveCategory.KEYWORD,
                        PrimitiveCategory.COMMENT,
                        PrimitiveCategory.NUMBER_LITERAL,
                        PrimitiveCategory.PARAMETER,
                        PrimitiveCategory.TYPE_REF,
                        PrimitiveCategory.LOCAL_VAR,
                        PrimitiveCategory.GENERICS,
                    ),
                ),
                PreviewSampleSpec(
                    "JavaScript",
                    "preset-preview.js",
                    "JavaScript",
                    "js",
                    "javascript.txt",
                    categorySet(
                        PrimitiveCategory.COMMENT,
                        PrimitiveCategory.DOCUMENTATION,
                        PrimitiveCategory.ANNOTATION,
                        PrimitiveCategory.LOCAL_VAR,
                        PrimitiveCategory.INSTANCE_FIELD,
                        PrimitiveCategory.KEYWORD,
                        PrimitiveCategory.CLASS_DECL,
                        PrimitiveCategory.NUMBER_LITERAL,
                        PrimitiveCategory.PARAMETER,
                        PrimitiveCategory.STRING_LITERAL,
                        PrimitiveCategory.TYPE_REF,
                        PrimitiveCategory.STATIC_FIELD,
                        PrimitiveCategory.OPERATOR,
                        PrimitiveCategory.FUNCTION_DECL,
                    ),
                ),
                PreviewSampleSpec(
                    "TypeScript",
                    "preset-preview.ts",
                    "TypeScript",
                    "ts",
                    "typescript.txt",
                    categorySet(
                        PrimitiveCategory.COMMENT,
                        PrimitiveCategory.DOCUMENTATION,
                        PrimitiveCategory.INTERFACE_DECL,
                        PrimitiveCategory.CLASS_DECL,
                        PrimitiveCategory.GENERICS,
                        PrimitiveCategory.TYPE_REF,
                        PrimitiveCategory.KEYWORD,
                        PrimitiveCategory.LOCAL_VAR,
                        PrimitiveCategory.PARAMETER,
                        PrimitiveCategory.FUNCTION_DECL,
                        PrimitiveCategory.ANNOTATION,
                        PrimitiveCategory.INSTANCE_FIELD,
                    ),
                ),
                PreviewSampleSpec(
                    "Go",
                    "preset_preview.go",
                    "Go",
                    "go",
                    "go.txt",
                    categorySet(
                        PrimitiveCategory.COMMENT,
                        PrimitiveCategory.NUMBER_LITERAL,
                        PrimitiveCategory.STRING_LITERAL,
                        PrimitiveCategory.FUNCTION_DECL,
                        PrimitiveCategory.TYPE_REF,
                        PrimitiveCategory.LOCAL_VAR,
                        PrimitiveCategory.KEYWORD,
                        PrimitiveCategory.INTERFACE_DECL,
                        PrimitiveCategory.CLASS_DECL,
                        PrimitiveCategory.PARAMETER,
                        PrimitiveCategory.INSTANCE_FIELD,
                        PrimitiveCategory.ANNOTATION,
                        PrimitiveCategory.OPERATOR,
                    ),
                ),
                PreviewSampleSpec(
                    "Rust",
                    "preset_preview.rs",
                    "Rust",
                    "rs",
                    "rust.txt",
                    categorySet(
                        PrimitiveCategory.COMMENT,
                        PrimitiveCategory.DOCUMENTATION,
                        PrimitiveCategory.NUMBER_LITERAL,
                        PrimitiveCategory.STRING_LITERAL,
                        PrimitiveCategory.FUNCTION_DECL,
                        PrimitiveCategory.ANNOTATION,
                        PrimitiveCategory.CLASS_DECL,
                        PrimitiveCategory.PARAMETER,
                        PrimitiveCategory.KEYWORD,
                        PrimitiveCategory.TYPE_REF,
                        PrimitiveCategory.OPERATOR,
                        PrimitiveCategory.GENERICS,
                        PrimitiveCategory.LOCAL_VAR,
                        PrimitiveCategory.STATIC_FIELD,
                    ),
                ),
                PreviewSampleSpec(
                    "CSS",
                    "preview.css",
                    "CSS",
                    "css",
                    "css.txt",
                    categorySet(
                        PrimitiveCategory.OPERATOR,
                        PrimitiveCategory.CLASS_DECL,
                        PrimitiveCategory.KEYWORD,
                        PrimitiveCategory.NUMBER_LITERAL,
                        PrimitiveCategory.STRING_LITERAL,
                        PrimitiveCategory.ANNOTATION,
                        PrimitiveCategory.LOCAL_VAR,
                    ),
                ),
                PreviewSampleSpec(
                    "HTML",
                    "preview.html",
                    "HTML",
                    "html",
                    "html.txt",
                    categorySet(
                        PrimitiveCategory.ANNOTATION,
                        PrimitiveCategory.STRING_LITERAL,
                        PrimitiveCategory.KEYWORD,
                    ),
                ),
                PreviewSampleSpec(
                    "JSON",
                    "preview.json",
                    "JSON",
                    "json",
                    "json.txt",
                    categorySet(PrimitiveCategory.NUMBER_LITERAL, PrimitiveCategory.STRING_LITERAL),
                ),
                PreviewSampleSpec(
                    "YAML",
                    "preview.yaml",
                    "YAML",
                    "yaml",
                    "yaml.txt",
                    categorySet(
                        PrimitiveCategory.STRING_LITERAL,
                        PrimitiveCategory.ANNOTATION,
                        PrimitiveCategory.OPERATOR,
                    ),
                ),
                PreviewSampleSpec(
                    "Markdown",
                    "preview.md",
                    "Markdown",
                    "md",
                    "markdown.txt",
                    categorySet(
                        PrimitiveCategory.KEYWORD,
                        PrimitiveCategory.DOCUMENTATION,
                        PrimitiveCategory.COMMENT,
                    ),
                ),
                PreviewSampleSpec(
                    "Swift",
                    "Preview.swift",
                    "Swift",
                    "swift",
                    "swift.txt",
                    categorySet(
                        PrimitiveCategory.CLASS_DECL,
                        PrimitiveCategory.PARAMETER,
                        PrimitiveCategory.TYPE_REF,
                        PrimitiveCategory.ANNOTATION,
                        PrimitiveCategory.KEYWORD,
                        PrimitiveCategory.FUNCTION_DECL,
                        PrimitiveCategory.INSTANCE_FIELD,
                        PrimitiveCategory.LOCAL_VAR,
                        PrimitiveCategory.NUMBER_LITERAL,
                        PrimitiveCategory.INTERFACE_DECL,
                        PrimitiveCategory.STRING_LITERAL,
                        PrimitiveCategory.GENERICS,
                        PrimitiveCategory.COMMENT,
                        PrimitiveCategory.STATIC_FIELD,
                        PrimitiveCategory.DOCUMENTATION,
                    ),
                ),
            ).associateBy(PreviewSampleSpec::language)

        private val DEFAULT_SAMPLE =
            PreviewSample(
                "Preview.txt",
                null,
                null,
                """
                class Preview {
                    value = "hello"
                    count = 42
                    // tune syntax colors
                }
                """.trimIndent(),
            )

        private fun fixedColor(rgb: Int): JBColor = JBColor(rgb, rgb)

        private fun categorySet(vararg categories: PrimitiveCategory): Set<PrimitiveCategory> = categories.toSet()

        @TestOnly
        internal fun catalogLanguagesForTest(): Set<String> = PREVIEW_SAMPLE_SPECS.keys.toSet()

        @TestOnly
        internal fun categoriesForTest(language: String): Set<PrimitiveCategory> =
            PREVIEW_SAMPLE_SPECS[language]?.demonstratedCategories.orEmpty()

        @TestOnly
        internal fun resourceNamesForTest(): Set<String> =
            PREVIEW_SAMPLE_SPECS.values.mapTo(linkedSetOf(), PreviewSampleSpec::resourceName)

        private fun normalizeLanguage(language: String): String =
            language.takeIf { it.isNotBlank() } ?: DEFAULT_LANGUAGE

        private fun sampleFor(language: String): PreviewSample =
            PREVIEW_SAMPLE_SPECS[normalizeLanguage(language)]?.toPreviewSample() ?: DEFAULT_SAMPLE

        private fun PreviewSampleSpec.toPreviewSample(): PreviewSample =
            PreviewSample(
                fileName,
                standardFileTypeName,
                defaultExtension,
                loadPreviewCode(resourceName),
            )

        private fun loadPreviewCode(resourceName: String): String {
            val resourcePath = "/dev/ayuislands/settings/syntax-preview/$resourceName"
            val stream = SyntaxPreviewComponent::class.java.getResourceAsStream(resourcePath)
            if (stream == null) {
                LOG.warn("Syntax preview sample resource '$resourcePath' is missing; falling back to default sample")
                return DEFAULT_SAMPLE.code
            }
            return stream.bufferedReader(Charsets.UTF_8).use { it.readText().trimIndent() }
        }

        private fun resolvePreviewFileType(
            language: String,
            sample: PreviewSample,
        ): PreviewFileType {
            val fileType = availableFileType(language, sample)
            return if (fileType == null) {
                PreviewFileType(PlainTextFileType.INSTANCE, isPlainTextFallback = true)
            } else {
                PreviewFileType(fileType, isPlainTextFallback = false)
            }
        }

        internal fun preferredAvailableLanguage(
            languages: List<String>,
            preferred: String,
        ): String =
            languages.firstOrNull { it == preferred && hasFileType(it) }
                ?: languages.firstOrNull(::hasFileType)
                ?: languages.firstOrNull { it == preferred }
                ?: languages.firstOrNull().orEmpty()

        private fun hasFileType(language: String): Boolean = availableFileType(language, sampleFor(language)) != null

        private fun availableFileType(
            language: String,
            sample: PreviewSample,
        ): FileType? =
            standardFileType(sample)
                ?: registeredLanguageFileType(language)

        private fun standardFileType(sample: PreviewSample): FileType? {
            val standardName = sample.standardFileTypeName ?: return null
            val extension = sample.defaultExtension
            return try {
                val fileType = FileTypeManager.getInstance().getStdFileType(standardName)
                if (fileType.name.equals(standardName, ignoreCase = true) ||
                    extension != null &&
                    fileType.defaultExtension.equals(extension, ignoreCase = true)
                ) {
                    fileType
                } else {
                    null
                }
            } catch (exception: RuntimeException) {
                LOG.debug("Standard file type '$standardName' unavailable for syntax preview", exception)
                null
            }
        }

        private fun registeredLanguageFileType(languageDisplayName: String): FileType? =
            try {
                val normalizedDisplayName = languageDisplayName.lowercase(Locale.ROOT)
                val language =
                    Language.getRegisteredLanguages().firstOrNull {
                        it.displayName.lowercase(Locale.ROOT) == normalizedDisplayName
                    }
                language?.let { FileTypeManager.getInstance().findFileTypeByLanguage(it) }
            } catch (exception: RuntimeException) {
                LOG.debug(
                    "Registered language lookup failed for syntax preview language '$languageDisplayName'",
                    exception,
                )
                null
            }
    }
}
