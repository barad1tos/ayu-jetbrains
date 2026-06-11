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
        val layout = previewLayout()
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

            paintRoundedSurface(g2, Rectangle(0, 0, width, height), JBUI.scale(ARC), editorSurface())

            val layout = previewLayout()
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

    private fun previewLayout(): PreviewLayout {
        val padding = JBUI.scale(PADDING)
        val projectWidth = JBUI.scale(PROJECT_WIDTH)
        val columnGap = JBUI.scale(COLUMN_GAP)
        val editorX = padding + projectWidth + columnGap
        return PreviewLayout(
            padding = padding,
            contentHeight = (height - padding * 2).coerceAtLeast(1),
            projectWidth = projectWidth,
            editorX = editorX,
            editorWidth = (width - editorX - padding).coerceAtLeast(1),
        )
    }

    private fun paintRoundedSurface(
        g2: Graphics2D,
        bounds: Rectangle,
        arc: Int,
        surface: Color,
    ) {
        g2.color = surface
        g2.fillRoundRect(bounds.x, bounds.y, bounds.width, bounds.height, arc, arc)
        g2.color = JBColor.border()
        g2.drawRoundRect(bounds.x, bounds.y, bounds.width - 1, bounds.height - 1, arc, arc)
    }

    private fun paintProjectPanel(
        g2: Graphics2D,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ) {
        paintRoundedSurface(g2, Rectangle(x, y, width, height), JBUI.scale(INNER_ARC), panelSurface())

        g2.font = JBUI.Fonts.smallFont()
        val rowHeight = JBUI.scale(PROJECT_ROW_HEIGHT)
        var rowY = y + JBUI.scale(PROJECT_TOP_PADDING)
        for (row in projectRows()) {
            val baseline = rowY + (rowHeight - g2.fontMetrics.height) / 2 + g2.fontMetrics.ascent
            g2.color = row.dotColor
            g2.fillOval(
                x + JBUI.scale(FILE_DOT_X),
                rowY + JBUI.scale(FILE_DOT_Y),
                JBUI.scale(FILE_DOT),
                JBUI.scale(FILE_DOT),
            )
            g2.color = UIUtil.getLabelForeground()
            g2.drawString(row.text, x + JBUI.scale(FILE_TEXT_X), baseline)
            rowY += rowHeight
        }
    }

    private fun paintEditorPanelFrame(
        g2: Graphics2D,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ) {
        paintRoundedSurface(g2, Rectangle(x, y, width, height), JBUI.scale(INNER_ARC), editorSurface())
    }

    private fun projectRows(): List<ProjectRow> =
        listOf(ProjectRow(LANGUAGE_FILE_DOT, previewSample.fileName)) + PROJECT_ROW_TAIL

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

    private data class PreviewLayout(
        val padding: Int,
        val contentHeight: Int,
        val projectWidth: Int,
        val editorX: Int,
        val editorWidth: Int,
    )

    private data class SurfacePalette(
        val editor: Color,
        val panel: Color,
    )

    private data class ProjectRow(
        val dotColor: Color,
        val text: String,
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
        private const val PADDING = 10
        private const val COLUMN_GAP = 10
        private const val PROJECT_WIDTH = 154
        private const val PROJECT_TOP_PADDING = 12
        private const val PROJECT_ROW_HEIGHT = 22
        private const val FILE_DOT_X = 11
        private const val FILE_DOT_Y = 8
        private const val FILE_DOT = 7
        private const val FILE_TEXT_X = 26
        private const val EDITOR_INSET = 1
        private const val ARC = 8
        private const val INNER_ARC = 6
        private const val DEFAULT_LANGUAGE = "Kotlin"

        private val DARK_PALETTE = SurfacePalette(fixedColor(0x0D1017), fixedColor(0x141923))
        private val MIRAGE_PALETTE = SurfacePalette(fixedColor(0x1F2430), fixedColor(0x252B38))
        private val LIGHT_PALETTE = SurfacePalette(fixedColor(0xFAFAFA), fixedColor(0xEFF2F5))
        private val LANGUAGE_FILE_DOT = fixedColor(0x59C2FF)
        private val PROJECT_ROW_TAIL =
            listOf(
                ProjectRow(fixedColor(0x7FD17F), "Config.java"),
                ProjectRow(fixedColor(0xFFA759), "Types.kt"),
                ProjectRow(fixedColor(0xFFD580), "build/"),
            )

        private val PREVIEW_SAMPLES =
            mapOf(
                "Kotlin" to
                    PreviewSample(
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
                        """.trimIndent(),
                    ),
                "Java" to
                    PreviewSample(
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
                        """.trimIndent(),
                    ),
                "Python" to
                    PreviewSample(
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
                        """.trimIndent(),
                    ),
                "JavaScript" to
                    PreviewSample(
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
                        """.trimIndent(),
                    ),
                "TypeScript" to
                    PreviewSample(
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
                        """.trimIndent(),
                    ),
                "Go" to
                    PreviewSample(
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
                        """.trimIndent(),
                    ),
                "Rust" to
                    PreviewSample(
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
                        """.trimIndent(),
                    ),
                "CSS" to
                    PreviewSample(
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
                        """.trimIndent(),
                    ),
                "HTML" to
                    PreviewSample(
                        "preview.html",
                        "HTML",
                        "html",
                        """
                        <section class="preview">
                            <!-- tune tags and text -->
                            <h1>Hello</h1>
                            <span data-count="42">Ayu Islands</span>
                        </section>
                        """.trimIndent(),
                    ),
                "JSON" to
                    PreviewSample(
                        "preview.json",
                        "JSON",
                        "json",
                        """
                        {
                          "name": "Ayu Islands",
                          "enabled": true,
                          "count": 42
                        }
                        """.trimIndent(),
                    ),
                "YAML" to
                    PreviewSample(
                        "preview.yaml",
                        "YAML",
                        "yaml",
                        """
                        name: Ayu Islands
                        enabled: true
                        count: 42
                        # tune keys and values
                        """.trimIndent(),
                    ),
                "Markdown" to
                    PreviewSample(
                        "preview.md",
                        "Markdown",
                        "md",
                        """
                        # Ayu Islands

                        `code` and **strong** text

                        - count: 42
                        """.trimIndent(),
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
