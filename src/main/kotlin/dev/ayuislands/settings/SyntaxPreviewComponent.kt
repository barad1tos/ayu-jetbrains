package dev.ayuislands.settings

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.logger
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
import java.awt.RenderingHints
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
) : JComponent(),
    Disposable {
    private val editorField: EditorTextField = createEditorField()
    private var isDisposed = false

    init {
        layout = null
        isOpaque = false
        toolTipText = "Syntax color preview"
        add(editorField)
    }

    fun updatePreview(variant: AyuVariant) {
        this.variant = variant
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

            val surface = editorSurface()
            val arc = JBUI.scale(ARC)
            g2.color = surface
            g2.fillRoundRect(0, 0, width, height, arc, arc)
            g2.color = JBColor.border()
            g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)

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
            CODE_SNIPPET,
            ProjectManager.getInstance().defaultProject,
            kotlinPreviewFileType(),
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

    private fun paintProjectPanel(
        g2: Graphics2D,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ) {
        val arc = JBUI.scale(INNER_ARC)
        g2.color = panelSurface()
        g2.fillRoundRect(x, y, width, height, arc, arc)
        g2.color = JBColor.border()
        g2.drawRoundRect(x, y, width - 1, height - 1, arc, arc)

        g2.font = JBUI.Fonts.smallFont()
        val rowHeight = JBUI.scale(PROJECT_ROW_HEIGHT)
        var rowY = y + JBUI.scale(PROJECT_TOP_PADDING)
        for (row in PROJECT_ROWS) {
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
        val arc = JBUI.scale(INNER_ARC)
        g2.color = editorSurface()
        g2.fillRoundRect(x, y, width, height, arc, arc)
        g2.color = JBColor.border()
        g2.drawRoundRect(x, y, width - 1, height - 1, arc, arc)
    }

    private data class PreviewLayout(
        val padding: Int,
        val contentHeight: Int,
        val projectWidth: Int,
        val editorX: Int,
        val editorWidth: Int,
    )

    private fun editorSurface(): Color =
        when (variant) {
            AyuVariant.DARK -> DARK_SURFACE
            AyuVariant.MIRAGE -> MIRAGE_SURFACE
            AyuVariant.LIGHT -> LIGHT_SURFACE
        }

    private fun panelSurface(): Color =
        when (variant) {
            AyuVariant.DARK -> DARK_PANEL_SURFACE
            AyuVariant.MIRAGE -> MIRAGE_PANEL_SURFACE
            AyuVariant.LIGHT -> LIGHT_PANEL_SURFACE
        }

    @TestOnly
    internal fun variantForTest(): AyuVariant = variant

    private data class ProjectRow(
        val dotColor: Color,
        val text: String,
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
        private const val KOTLIN_FILE_TYPE_NAME = "Kotlin"
        private const val KOTLIN_FILE_EXTENSION = "kt"

        private val DARK_SURFACE = Color(0x0D1017)
        private val MIRAGE_SURFACE = Color(0x1F2430)
        private val LIGHT_SURFACE = Color(0xFAFAFA)
        private val DARK_PANEL_SURFACE = Color(0x141923)
        private val MIRAGE_PANEL_SURFACE = Color(0x252B38)
        private val LIGHT_PANEL_SURFACE = Color(0xEFF2F5)

        private val PROJECT_ROWS =
            listOf(
                ProjectRow(Color(0x59C2FF), "PresetPreview.kt"),
                ProjectRow(Color(0x7FD17F), "Config.java"),
                ProjectRow(Color(0xFFA759), "Types.kt"),
                ProjectRow(Color(0xFFD580), "build/"),
            )

        private val CODE_SNIPPET =
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
            """.trimIndent()

        private fun kotlinPreviewFileType(): FileType =
            try {
                val fileType = FileTypeManager.getInstance().getStdFileType(KOTLIN_FILE_TYPE_NAME)
                if (fileType.name == KOTLIN_FILE_TYPE_NAME || fileType.defaultExtension == KOTLIN_FILE_EXTENSION) {
                    fileType
                } else {
                    PlainTextFileType.INSTANCE
                }
            } catch (exception: RuntimeException) {
                LOG.debug("Falling back to plain text for syntax preview", exception)
                PlainTextFileType.INSTANCE
            }
    }
}
