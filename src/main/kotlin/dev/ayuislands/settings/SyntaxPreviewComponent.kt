package dev.ayuislands.settings

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.EditorTextField
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.syntax.LanguageSpecification
import dev.ayuislands.syntax.NativeProfile
import dev.ayuislands.syntax.PreviewFileSpec
import dev.ayuislands.syntax.PrimitiveCategory
import dev.ayuislands.syntax.SyntaxLanguageRegistry
import org.jetbrains.annotations.TestOnly
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.SwingConstants

/**
 * Live preview of syntax-intensity colors rendered as a compact IDE scene.
 *
 * The project tree is a lightweight frame, while the code pane is a native
 * [EditorTextField] so syntax colors come from the active IntelliJ editor
 * highlighter and color scheme instead of a hand-painted token imitation.
 * The highlighter receives an in-memory file with the sample's real name and
 * type, allowing language plugins to select the same lexer they use for files
 * in the main editor.
 */
internal class SyntaxPreviewComponent(
    private var variant: AyuVariant,
    private var language: String = DEFAULT_LANGUAGE,
    private val previewCodeLoader: (String) -> String = ::loadPreviewCode,
    private val previewResolver: NativePreviewResolver = NativePreviewResolver(),
    private val previewFileFactory: (String, FileType, CharSequence) -> VirtualFile = ::createPreviewFile,
) : JComponent(),
    Disposable {
    private var previewSample: PreviewSample = sampleFor(language)
    private var fileTypeResolution: PreviewFileType = resolvePreviewFileType(previewSample)
    private val previewCodeCache = mutableMapOf<String, String>()
    private val editorField: EditorTextField = createEditorField()
    private val recoveryLabel =
        JLabel("", SwingConstants.CENTER).apply {
            foreground = UIUtil.getContextHelpForeground()
            isVisible = false
        }
    private val failedFactoryLanguages = mutableSetOf<String>()
    private val failedNativeLanguages = mutableSetOf<String>()
    private val failedPlainLanguages = mutableSetOf<String>()
    private val failedResolutionLanguages = mutableSetOf<String>()
    private var isDisposed = false

    init {
        layout = null
        isOpaque = false
        refreshFallbackTooltip(normalizeLanguage(language), fileTypeResolution)
        add(editorField)
        add(recoveryLabel)
    }

    fun updatePreview(
        variant: AyuVariant,
        language: String = this.language,
    ) {
        this.variant = variant
        val nextLanguage = normalizeLanguage(language)
        val nextSample = sampleFor(nextLanguage)
        if (nextLanguage != this.language || nextSample != previewSample) {
            selectSample(nextLanguage, nextSample)
        }
        refreshEditor()
        editorField.background = surfacePalette().editor
        editorField.repaint()
        revalidate()
        repaint()
    }

    fun showCapability(state: SyntaxCapabilityState?) {
        val unavailable = state as? SyntaxCapabilityState.PluginUnavailable
        recoveryLabel.text = unavailable?.recovery?.instruction.orEmpty()
        recoveryLabel.isVisible = unavailable != null
        editorField.isVisible = unavailable == null
        revalidate()
        repaint()
    }

    fun showPrimitive(category: PrimitiveCategory) {
        val specification = SyntaxLanguageRegistry.findByStorageId(language) ?: return
        val previewFileAndCode =
            specification.preview.files
                .asSequence()
                .filter { category in it.demonstratedCategories }
                .map { previewFile ->
                    previewFile to
                        previewCodeCache.getOrPut(previewFile.resourceName) {
                            previewCodeLoader(previewFile.resourceName)
                        }
                }.minByOrNull { (_, code) -> code.length }
                ?: return
        val (previewFile, previewCode) = previewFileAndCode
        val nextSample =
            PreviewSample(
                fileName = previewFile.fileName,
                profile = specification.profile(previewFile),
                code = previewCode,
            )
        if (nextSample == previewSample) return
        selectSample(language, nextSample)
        refreshEditor()
        editorField.repaint()
        revalidate()
        repaint()
    }

    private fun selectSample(
        nextLanguage: String,
        nextSample: PreviewSample,
    ) {
        language = nextLanguage
        previewSample = nextSample
        fileTypeResolution = resolvePreviewFileType(nextSample)
        val document = EditorFactory.getInstance().createDocument(nextSample.code)
        editorField.setNewDocumentAndFileType(fileTypeResolution.fileType, document)
        refreshFallbackTooltip(nextLanguage, fileTypeResolution)
    }

    private fun refreshEditor(editor: EditorEx? = editorField.getEditor(false)) {
        editor ?: return
        val previewScheme = previewColorsScheme(editor.colorsScheme)
        editor.colorsScheme = previewScheme
        installPreviewHighlighter(editor, previewScheme)
    }

    private fun installPreviewHighlighter(
        editor: EditorEx,
        scheme: EditorColorsScheme,
    ) {
        val project = editorField.project ?: return
        val previewFile =
            previewFileFactory(
                previewSample.fileName,
                fileTypeResolution.fileType,
                previewSample.code,
            )
        val factory =
            try {
                EditorHighlighterFactory.getInstance()
            } catch (runtime: RuntimeException) {
                propagateCancellation(runtime)
                if (failedFactoryLanguages.add(language)) {
                    LOG.warn("Syntax highlighter service failed for preview language '$language'", runtime)
                }
                return
            }
        val highlighter =
            try {
                factory.createEditorHighlighter(previewFile, scheme, project)
            } catch (runtime: RuntimeException) {
                propagateCancellation(runtime)
                if (failedNativeLanguages.add(language)) {
                    LOG.warn("Native syntax highlighter failed for preview language '$language'", runtime)
                }
                try {
                    factory.createEditorHighlighter(PlainTextFileType.INSTANCE, scheme, project)
                } catch (fallbackFailure: RuntimeException) {
                    propagateCancellation(fallbackFailure)
                    if (failedPlainLanguages.add(language)) {
                        LOG.warn("Plain-text highlighter failed for preview language '$language'", fallbackFailure)
                    }
                    return
                }
            }
        editor.highlighter = highlighter
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
        recoveryLabel.bounds = editorField.bounds
    }

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        val g2 = graphics.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

            PreviewChromePainter.paintOuterPanel(g2, width, height, surfacePalette().editor)

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
        object : EditorTextField(
            previewSample.code,
            ProjectManager.getInstance().defaultProject,
            fileTypeResolution.fileType,
        ) {
            override fun onEditorAdded(editor: Editor) {
                super.onEditorAdded(editor)
                if (editor is EditorEx) refreshEditor(editor)
            }
        }.apply {
            isViewer = true
            setDisposedWith(this@SyntaxPreviewComponent)
            background = surfacePalette().editor
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
                    surface = surfacePalette().panel,
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
        PreviewChromePainter.paintPanelFrame(g2, Rectangle(x, y, width, height), surfacePalette().editor)
    }

    private fun projectRows(): List<PreviewChromeProjectRow> =
        SyntaxLanguageRegistry
            .findByStorageId(language)
            ?.preview
            ?.files
            .orEmpty()
            .map { previewFile -> PreviewChromeProjectRow(LANGUAGE_FILE_DOT, previewFile.fileName) }
            .ifEmpty { listOf(PreviewChromeProjectRow(LANGUAGE_FILE_DOT, previewSample.fileName)) } + PROJECT_ROW_TAIL

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
        val profile: NativeProfile?,
        val code: String,
    )

    private data class PreviewFileType(
        val fileType: FileType,
        val isPlainTextFallback: Boolean,
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
        private val PLAIN_TEXT_PREVIEW =
            PreviewFileType(PlainTextFileType.INSTANCE, isPlainTextFallback = true)

        private val DEFAULT_SAMPLE =
            PreviewSample(
                "Preview.txt",
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

        private fun createPreviewFile(
            name: String,
            fileType: FileType,
            content: CharSequence,
        ): VirtualFile = LightVirtualFile(name, fileType, content)

        @TestOnly
        internal fun catalogLanguagesForTest(): Set<String> =
            SyntaxLanguageRegistry.specifications().mapTo(linkedSetOf(), LanguageSpecification::storageId)

        @TestOnly
        internal fun categoriesForTest(language: String) =
            SyntaxLanguageRegistry
                .findByStorageId(language)
                ?.preview
                ?.files
                .orEmpty()
                .flatMapTo(linkedSetOf(), PreviewFileSpec::demonstratedCategories)

        private fun normalizeLanguage(language: String): String =
            language.takeIf { it.isNotBlank() } ?: DEFAULT_LANGUAGE

        private fun sampleFor(language: String): PreviewSample =
            SyntaxLanguageRegistry
                .findByStorageId(normalizeLanguage(language))
                ?.let(::firstPreviewSample)
                ?: DEFAULT_SAMPLE

        private fun firstPreviewSample(specification: LanguageSpecification): PreviewSample {
            val previewFile = specification.preview.files.firstOrNull() ?: return DEFAULT_SAMPLE
            return previewFile.toPreviewSample(specification.profile(previewFile))
        }

        private fun LanguageSpecification.profile(previewFile: PreviewFileSpec) =
            checkNotNull(nativeProfiles.firstOrNull { it.id == previewFile.profileId }) {
                "Unknown native profile '${previewFile.profileId}' for '$storageId'"
            }

        private fun PreviewFileSpec.toPreviewSample(profile: NativeProfile): PreviewSample =
            PreviewSample(
                fileName,
                profile,
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
    }

    private fun resolvePreviewFileType(sample: PreviewSample): PreviewFileType {
        val profile = sample.profile ?: return PLAIN_TEXT_PREVIEW
        return when (val resolution = previewResolver.resolve(sample.fileName, profile)) {
            is NativePreviewResolution.Resolved ->
                PreviewFileType(resolution.fileType, isPlainTextFallback = false)
            is NativePreviewResolution.Unavailable -> PLAIN_TEXT_PREVIEW
            is NativePreviewResolution.LookupFailed -> {
                if (failedResolutionLanguages.add(language)) {
                    LOG.warn("Native file type lookup failed for preview language '$language'", resolution.failure)
                }
                PLAIN_TEXT_PREVIEW
            }
        }
    }
}

private fun propagateCancellation(failure: RuntimeException) = rethrowPreviewCancellation(failure)
