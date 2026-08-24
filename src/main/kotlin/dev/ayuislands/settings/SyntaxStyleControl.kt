package dev.ayuislands.settings

import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.ui.InplaceButton
import com.intellij.ui.JBColor
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.ayuislands.syntax.FontEmphasis
import dev.ayuislands.syntax.PrimitiveCategory
import java.awt.Font
import java.awt.event.ActionListener
import javax.swing.JCheckBox

/** Compact selector for additive font emphasis on one syntax category. */
internal class SyntaxStyleControl(
    private val category: PrimitiveCategory,
    private val language: () -> String,
    private val emphasis: () -> FontEmphasis?,
    private val onEmphasisChanged: (FontEmphasis?) -> Unit,
) {
    private var popup: JBPopup? = null

    val component: InplaceButton =
        InplaceButton("Font style for ${category.displayName}", presentationIcon()) {
            showPopup()
        }.apply {
            isFocusable = true
        }

    init {
        refreshPresentation()
    }

    fun refreshPresentation() {
        val current = emphasis()
        component.icon = presentationIcon(current)
        component.accessibleContext.accessibleName =
            "Font style — ${category.displayName}, ${language()}"
        component.accessibleContext.accessibleDescription =
            "Adds bold or italic emphasis to the inherited syntax style."
    }

    fun rebind() {
        closePopup()
        refreshPresentation()
    }

    fun dispose() {
        closePopup()
    }

    private fun showPopup() {
        closePopup()
        val current = emphasis()
        lateinit var boldCheckBox: JCheckBox
        lateinit var italicCheckBox: JCheckBox
        val content =
            panel {
                row {
                    boldCheckBox =
                        checkBox("Bold").component.apply {
                            isSelected = current?.isBold == true
                        }
                }
                row {
                    italicCheckBox =
                        checkBox("Italic").component.apply {
                            isSelected = current?.isItalic == true
                            font = font.deriveFont(Font.ITALIC)
                        }
                }
                row { comment("Adds to the inherited style.") }
            }

        val publish =
            ActionListener {
                onEmphasisChanged(
                    FontEmphasis.fromFlags(
                        isBold = boldCheckBox.isSelected,
                        isItalic = italicCheckBox.isSelected,
                    ),
                )
                refreshPresentation()
            }
        boldCheckBox.addActionListener(publish)
        italicCheckBox.addActionListener(publish)

        val created =
            JBPopupFactory
                .getInstance()
                .createComponentPopupBuilder(content, boldCheckBox)
                .setTitle(category.displayName)
                .setRequestFocus(true)
                .setCancelOnClickOutside(true)
                .setCancelOnWindowDeactivation(false)
                .setMovable(false)
                .setResizable(false)
                .setCancelKeyEnabled(true)
                .createPopup()
        popup = created
        created.addListener(
            object : JBPopupListener {
                override fun onClosed(event: LightweightWindowEvent) {
                    if (popup === created) popup = null
                }
            },
        )
        created.showUnderneathOf(component)
    }

    private fun closePopup() {
        val openPopup = popup ?: return
        popup = null
        openPopup.cancel()
    }

    private fun presentationIcon(current: FontEmphasis? = emphasis()): StyleGlyphIcon {
        val isActive = current != null
        return StyleGlyphIcon(
            glyph = glyphFor(current),
            glyphStyle = current?.fontType ?: Font.PLAIN,
            foreground = if (isActive) UIUtil.getLabelForeground() else UIUtil.getContextHelpForeground(),
            background = if (isActive) JBUI.CurrentTheme.ActionButton.pressedBackground() else null,
            border =
                if (isActive) {
                    JBColor.namedColor(
                        "Popup.innerBorderColor",
                        JBColor.namedColor("Popup.borderColor", JBColor.GRAY),
                    )
                } else {
                    null
                },
        )
    }

    internal companion object {
        fun glyphFor(emphasis: FontEmphasis?): String =
            when (emphasis) {
                null -> "Aa"
                FontEmphasis.BOLD -> "B"
                FontEmphasis.ITALIC -> "I"
                FontEmphasis.BOLD_ITALIC -> "BI"
            }
    }
}
