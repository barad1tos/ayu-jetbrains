package dev.ayuislands.settings

import com.intellij.lang.Language
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.EditorFactory
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
    private val editorField: EditorTextField = createEditorField()
    private var isDisposed = false

    init {
        layout = null
        isOpaque = false
        toolTipText = "Syntax color preview"
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
            val document = EditorFactory.getInstance().createDocument(nextSample.code)
            editorField.setNewDocumentAndFileType(previewFileType(nextLanguage, nextSample), document)
        }
        editorField.background = editorSurface()
        editorField.repaint()
        revalidate()
        repaint()
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
            previewFileType(language, previewSample),
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

    private companion object {
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

        private val PREVIEW_SAMPLES =
            mapOf(
                previewSample(
                    "Kotlin",
                    "PresetPreview.kt",
                    "Kotlin",
                    "kt",
                    """
                    fun main() {
                        val msg = "hello"
                        val count = 42
                        // print greeting
                        /** Greet the user */
                        class Greeter {
                            @JvmStatic
                            fun greet(name: String) {
                                if (msg.isNotEmpty()) println(name)
                            }
                        }
                    }
                    """,
                ),
                previewSample(
                    "Java",
                    "PresetPreview.java",
                    "JAVA",
                    "java",
                    """
                    public final class Greeter {
                        private static final String MSG = "hello";
                        // print greeting
                        /** Greet the user */
                        public void greet(String name) {
                            if (!MSG.isEmpty()) {
                                System.out.println(name);
                            }
                        }
                    }
                    """,
                ),
                previewSample(
                    "Python",
                    "preset_preview.py",
                    "Python",
                    "py",
                    """
                    class Greeter:
                        # Greet the user.
                        def greet(self, name: str) -> None:
                            msg = "hello"
                            count = 42
                            if msg:
                                print(name, count)
                    """,
                ),
                previewSample(
                    "JavaScript",
                    "preset-preview.js",
                    "JavaScript",
                    "js",
                    """
                    export function greet(name) {
                        const msg = "hello";
                        const count = 42;
                        // print greeting
                        if (msg.length > 0) {
                            console.log(name, count);
                        }
                    }
                    """,
                ),
                previewSample(
                    "TypeScript",
                    "preset-preview.ts",
                    "TypeScript",
                    "ts",
                    """
                    export function greet(name: string): void {
                        const msg = "hello";
                        const count = 42;
                        // print greeting
                        if (msg.length > 0) {
                            console.log(name, count);
                        }
                    }
                    """,
                ),
                previewSample(
                    "Go",
                    "preset_preview.go",
                    "Go",
                    "go",
                    """
                    package preview

                    import "fmt"

                    // Greeter prints a greeting.
                    func Greet(name string) {
                        msg := "hello"
                        count := 42
                        if len(msg) > 0 {
                            fmt.Println(name, count)
                        }
                    }
                    """,
                ),
                previewSample(
                    "Rust",
                    "preset_preview.rs",
                    "Rust",
                    "rs",
                    """
                    pub fn greet(name: &str) {
                        let msg = "hello";
                        let count = 42;
                        // print greeting
                        if !msg.is_empty() {
                            println!("{}", name);
                        }
                    }
                    """,
                ),
                previewSample(
                    "CSS",
                    "preview.css",
                    "CSS",
                    "css",
                    """
                    .preview {
                        color: #ffcc66;
                        padding: 12px;
                        /* tune declarations */
                        border-radius: 6px;
                    }
                    """,
                ),
                previewSample(
                    "HTML",
                    "preview.html",
                    "HTML",
                    "html",
                    """
                    <section class="preview">
                        <!-- tune tags and text -->
                        <h1>Hello</h1>
                        <span data-count="42">Ayu Islands</span>
                    </section>
                    """,
                ),
                previewSample(
                    "JSON",
                    "preview.json",
                    "JSON",
                    "json",
                    """
                    {
                      "name": "Ayu Islands",
                      "enabled": true,
                      "count": 42
                    }
                    """,
                ),
                previewSample(
                    "YAML",
                    "preview.yaml",
                    "YAML",
                    "yaml",
                    """
                    name: Ayu Islands
                    enabled: true
                    count: 42
                    # tune keys and values
                    """,
                ),
                previewSample(
                    "Markdown",
                    "preview.md",
                    "Markdown",
                    "md",
                    """
                    # Ayu Islands

                    `code` and **strong** text

                    - count: 42
                    """,
                ),
            )

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

        private fun previewSample(
            language: String,
            fileName: String,
            standardFileTypeName: String,
            defaultExtension: String,
            code: String,
        ): Pair<String, PreviewSample> =
            language to
                PreviewSample(
                    fileName,
                    standardFileTypeName,
                    defaultExtension,
                    code.trimIndent(),
                )

        private fun normalizeLanguage(language: String): String =
            language.takeIf { it.isNotBlank() } ?: DEFAULT_LANGUAGE

        private fun sampleFor(language: String): PreviewSample =
            PREVIEW_SAMPLES[normalizeLanguage(language)] ?: DEFAULT_SAMPLE

        private fun previewFileType(
            language: String,
            sample: PreviewSample,
        ): FileType =
            standardFileType(sample)
                ?: registeredLanguageFileType(language)
                ?: PlainTextFileType.INSTANCE

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
