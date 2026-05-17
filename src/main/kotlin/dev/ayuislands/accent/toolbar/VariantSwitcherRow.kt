package dev.ayuislands.accent.toolbar

import com.intellij.ide.ui.LafManager
import com.intellij.openapi.diagnostic.logger
import dev.ayuislands.accent.AyuVariant
import java.awt.FlowLayout
import javax.swing.ButtonGroup
import javax.swing.JCheckBox
import javax.swing.JPanel
import javax.swing.JRadioButton

/**
 * Three radio buttons (one per [AyuVariant] entry) + an Islands UI checkbox. Selection
 * resolves the exact theme name via [VariantThemeNameResolver.resolveThemeName] and
 * applies it through [LafManager.setCurrentLookAndFeel] (Pitfall 5 — second arg is
 * `lockEditorScheme = false`, so the same-named editor scheme follows the theme switch).
 *
 * The Islands UI checkbox is bound to the current radio selection; toggling it re-applies
 * the currently selected variant with the new chrome flavour. If [LafManager.findLaf]
 * returns `null` (theme drift between the AyuVariant enum and the installed themes), the
 * handler logs WARN and returns without touching LAF — Pitfall 7 fail-safe.
 */
internal class VariantSwitcherRow(
    initialVariant: AyuVariant,
) {
    val component: JPanel

    init {
        @Suppress("UnstableApiUsage")
        val islandsUiInitial =
            LafManager
                .getInstance()
                .currentUIThemeLookAndFeel
                ?.name
                ?.contains(ISLANDS_UI_SUFFIX) == true
        val islandsUiCheckbox = JCheckBox("Islands UI", islandsUiInitial)
        val group = ButtonGroup()
        val radios =
            AyuVariant.entries.map { variant ->
                JRadioButton(variant.label()).apply {
                    isSelected = variant == initialVariant
                    addActionListener {
                        if (isSelected) {
                            applyVariantAndChrome(variant, islandsUiCheckbox.isSelected)
                        }
                    }
                    group.add(this)
                }
            }
        islandsUiCheckbox.addActionListener {
            val selectedVariant =
                AyuVariant.entries.firstOrNull { variant ->
                    radios.first { it.text.equals(variant.name, ignoreCase = true) }.isSelected
                } ?: initialVariant
            applyVariantAndChrome(selectedVariant, islandsUiCheckbox.isSelected)
        }
        component =
            JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                radios.forEach { add(it) }
                add(islandsUiCheckbox)
            }
    }

    @Suppress("UnstableApiUsage") // findLaf + setCurrentLookAndFeel(UIThemeLookAndFeelInfo, Boolean)
    private fun applyVariantAndChrome(
        variant: AyuVariant,
        islandsUi: Boolean,
    ) {
        val themeName = VariantThemeNameResolver.resolveThemeName(variant, islandsUi)
        val lafManager = LafManager.getInstance()
        val laf =
            lafManager.findLaf(themeName) ?: run {
                LOG.warn("Theme not found for variant=$variant islandsUi=$islandsUi name='$themeName'")
                return
            }
        // Second arg is `lockEditorScheme = false` (Pitfall 5) — let the same-named
        // editor scheme follow the theme switch instead of locking on the old variant.
        lafManager.setCurrentLookAndFeel(laf, false)
        lafManager.updateUI()
        // AccentApplicator's existing LafManagerListener picks up the change and re-applies accent.
    }

    private companion object {
        const val ISLANDS_UI_SUFFIX = "(Islands UI)"
        val LOG = logger<VariantSwitcherRow>()

        fun AyuVariant.label(): String = name.lowercase().replaceFirstChar { it.uppercase() }
    }
}
