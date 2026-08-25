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
import dev.ayuislands.syntax.FontStyleOverride
import dev.ayuislands.syntax.PrimitiveCategory
import java.awt.Font
import java.awt.event.ActionListener
import javax.swing.JCheckBox

/** Compact selector for additive or exact font styling on one syntax category. */
internal class SyntaxStyleControl(
    private val category: PrimitiveCategory,
    private val language: () -> String,
    private val emphasis: () -> FontEmphasis?,
    private val onEmphasisChanged: (FontEmphasis?) -> Unit,
    private val styleOverride: () -> FontStyleOverride? = { null },
    private val onStyleOverrideChanged: (FontStyleOverride?) -> Unit = {},
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
        component.icon = presentationIcon()
        component.accessibleContext.accessibleName =
            "Font style — ${category.displayName}, ${language()}"
        component.accessibleContext.accessibleDescription = EMPHASIS_DESCRIPTION
    }

    fun setAvailable(
        isAvailable: Boolean,
        unavailableReason: String?,
    ) {
        if (!isAvailable) closePopup()
        component.isEnabled = isAvailable
        component.toolTipText = unavailableReason
        component.accessibleContext.accessibleDescription = unavailableReason ?: EMPHASIS_DESCRIPTION
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
        val currentOverride = styleOverride()
        val currentEmphasis = emphasis()
        val currentFontType = combinedFontType(currentOverride, currentEmphasis)
        lateinit var replaceCheckBox: JCheckBox
        lateinit var boldCheckBox: JCheckBox
        lateinit var italicCheckBox: JCheckBox
        val content =
            panel {
                row {
                    replaceCheckBox =
                        checkBox("Replace inherited style").component.apply {
                            isSelected = currentOverride != null
                        }
                }
                row {
                    boldCheckBox =
                        checkBox("Bold").component.apply {
                            isSelected = currentFontType and Font.BOLD != 0
                        }
                }
                row {
                    italicCheckBox =
                        checkBox("Italic").component.apply {
                            isSelected = currentFontType and Font.ITALIC != 0
                            font = font.deriveFont(Font.ITALIC)
                        }
                }
                row { comment("Replace lets unchecked styles become regular.") }
            }

        val publish =
            ActionListener {
                if (replaceCheckBox.isSelected) {
                    onEmphasisChanged(null)
                    onStyleOverrideChanged(
                        FontStyleOverride.fromFlags(
                            isBold = boldCheckBox.isSelected,
                            isItalic = italicCheckBox.isSelected,
                        ),
                    )
                } else {
                    onStyleOverrideChanged(null)
                    onEmphasisChanged(
                        FontEmphasis.fromFlags(
                            isBold = boldCheckBox.isSelected,
                            isItalic = italicCheckBox.isSelected,
                        ),
                    )
                }
                refreshPresentation()
            }
        replaceCheckBox.addActionListener(publish)
        boldCheckBox.addActionListener(publish)
        italicCheckBox.addActionListener(publish)

        val created =
            JBPopupFactory
                .getInstance()
                .createComponentPopupBuilder(content, replaceCheckBox)
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

    private fun presentationIcon(): StyleGlyphIcon {
        val currentOverride = styleOverride()
        val currentEmphasis = emphasis()
        val fontType = combinedFontType(currentOverride, currentEmphasis)
        val isActive = currentOverride != null || currentEmphasis != null
        return StyleGlyphIcon(
            glyph = glyphFor(currentOverride, currentEmphasis),
            glyphStyle = fontType,
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
        private const val EMPHASIS_DESCRIPTION =
            "Adds emphasis or replaces inherited bold and italic styling."

        fun glyphFor(emphasis: FontEmphasis?): String = glyphFor(null, emphasis)

        fun glyphFor(
            styleOverride: FontStyleOverride?,
            emphasis: FontEmphasis?,
        ): String {
            if (styleOverride == null && emphasis == null) return "Aa"
            return when (combinedFontType(styleOverride, emphasis)) {
                Font.PLAIN -> "R"
                Font.BOLD -> "B"
                Font.ITALIC -> "I"
                else -> "BI"
            }
        }

        private fun combinedFontType(
            styleOverride: FontStyleOverride?,
            emphasis: FontEmphasis?,
        ): Int = (styleOverride?.fontType ?: Font.PLAIN) or (emphasis?.fontType ?: Font.PLAIN)
    }
}
