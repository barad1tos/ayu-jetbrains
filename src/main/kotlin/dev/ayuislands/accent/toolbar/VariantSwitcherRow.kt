package dev.ayuislands.accent.toolbar

import com.intellij.openapi.diagnostic.logger
import com.intellij.util.ui.JBUI
import dev.ayuislands.AyuLaf
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AccentResolver
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.rethrowIfCancelled
import dev.ayuislands.accent.toolbar.popup.Density
import dev.ayuislands.accent.toolbar.popup.IslandsUiPill
import dev.ayuislands.accent.toolbar.popup.SegmentedControl
import dev.ayuislands.studio.StudioService
import dev.ayuislands.studio.model.StudioNativeChange
import dev.ayuislands.studio.model.StudioThemeChange
import java.awt.BorderLayout
import javax.swing.JPanel

/**
 * Variant row inside the quick-switcher popup: a 3-cell [SegmentedControl]
 * (Mirage / Dark / Light) on the left, plus an [IslandsUiPill] toggle on the
 * right.
 *
 * Both children submit the exact variant/Islands choice through the shared Studio
 * coordinator. Native theme and editor-scheme changes settle together before acceptance.
 *
 * Pattern A — segmented/pill mouse callbacks run on EDT by Swing contract.
 */
internal class VariantSwitcherRow(
    initialVariant: AyuVariant,
) {
    val component: JPanel

    private var currentVariant: AyuVariant = initialVariant
    private var islandsUi: Boolean

    init {
        islandsUi = AyuLaf.currentThemeName().contains(ISLANDS_UI_SUFFIX)

        val segmented =
            SegmentedControl(initialVariant) { selected ->
                currentVariant = selected
                applyVariantAndChrome(selected, islandsUi)
            }
        val pill =
            IslandsUiPill(
                initialSelected = islandsUi,
                accentSupplier = { resolveCurrentAccent() },
                onToggle = { newValue ->
                    islandsUi = newValue
                    applyVariantAndChrome(currentVariant, newValue)
                },
            )
        component =
            JPanel(BorderLayout(JBUI.scale(Density.CARD_CONTENT_PAD), 0)).apply {
                isOpaque = false
                add(segmented, BorderLayout.WEST)
                add(pill, BorderLayout.EAST)
            }
    }

    private fun resolveCurrentAccent(): String? {
        val variant = AyuVariant.detect() ?: currentVariant
        val project = AccentApplicator.resolveFocusedProject()
        return try {
            AccentResolver.resolve(project, variant)
        } catch (exception: RuntimeException) {
            LOG.warn("Variant row accent resolve failed", exception)
            null
        }
    }

    private fun applyVariantAndChrome(
        variant: AyuVariant,
        islandsUi: Boolean,
    ) {
        try {
            val themeName = VariantThemeNameResolver.resolveThemeName(variant, islandsUi)
            StudioService.getInstance().nativeChanged(
                StudioNativeChange.Theme(StudioThemeChange.Explicit(themeName)),
            )
        } catch (exception: RuntimeException) {
            exception.rethrowIfCancelled()
            LOG.warn(
                "Variant/Islands apply failed (variant=$variant islandsUi=$islandsUi)",
                exception,
            )
        }
    }

    private companion object {
        const val ISLANDS_UI_SUFFIX = "(Islands UI)"
        val LOG = logger<VariantSwitcherRow>()
    }
}
