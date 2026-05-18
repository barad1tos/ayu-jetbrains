package dev.ayuislands.accent.toolbar

import com.intellij.ide.ui.LafManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.util.ui.JBUI
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AccentDefaults
import dev.ayuislands.accent.AccentResolver
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.toolbar.popup.Density
import dev.ayuislands.accent.toolbar.popup.IslandsUiPill
import dev.ayuislands.accent.toolbar.popup.SegmentedControl
import java.awt.BorderLayout
import javax.swing.JPanel

/**
 * Variant row inside the quick-switcher popup: a 3-cell [SegmentedControl]
 * (Mirage / Dark / Light) on the left, plus an [IslandsUiPill] toggle on the
 * right.
 *
 * Both children share a single `applyVariantAndChrome(variant, islandsUi)`
 * sink that resolves the exact theme via
 * [VariantThemeNameResolver.resolveThemeName] and applies it through
 * `LafManager.setCurrentLookAndFeel(laf, false)`. The second argument is
 * `lockEditorScheme = false` so the same-named editor scheme follows the
 * theme switch instead of locking on the old.
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
        @Suppress("UnstableApiUsage")
        islandsUi =
            LafManager
                .getInstance()
                .currentUIThemeLookAndFeel
                ?.name
                ?.contains(ISLANDS_UI_SUFFIX) == true

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

    private fun resolveCurrentAccent(): String {
        val variant = AyuVariant.detect() ?: currentVariant
        val project = AccentApplicator.resolveFocusedProject()
        return try {
            AccentResolver.resolve(project, variant)
        } catch (exception: RuntimeException) {
            LOG.warn("Variant row accent resolve failed", exception)
            AccentDefaults.MIRAGE_HEX
        }
    }

    @Suppress("UnstableApiUsage") // getInstalledThemes + setCurrentLookAndFeel(UIThemeLookAndFeelInfo, Boolean)
    private fun applyVariantAndChrome(
        variant: AyuVariant,
        islandsUi: Boolean,
    ) {
        // Pattern B — every API call in this body can throw RuntimeException:
        //   - `VariantThemeNameResolver.resolveThemeName` raises
        //     [IllegalStateException] when the [AyuVariant.themeNames] set drifts
        //     (enum-rename regression).
        //   - `LafManager.setCurrentLookAndFeel` / `updateUI` can throw on a
        //     plugin install/uninstall race or a malformed `UIThemeLookAndFeelInfo`.
        // Without this wrapper, a transient throw lands as SEVERE in idea.log and
        // tears down the popup mouse-handler chain. The user-visible failure mode
        // is "popup goes blank after one click". Swallow and WARN so the next
        // attempt succeeds.
        try {
            val themeName = VariantThemeNameResolver.resolveThemeName(variant, islandsUi)
            val lafManager = LafManager.getInstance()
            // `findLaf(String)` on IDEA 2026.1 expects the platform theme *id* (e.g.
            // `com.ayuislands.theme.mirage`), not the user-visible *name* ("Ayu Mirage").
            // Match by name across the installed-themes sequence so this stays decoupled
            // from plugin.xml `themeProvider` ids — if those ids ever change, this still
            // resolves as long as the display names in `themes/*.theme.json` match.
            val laf =
                lafManager.installedThemes.firstOrNull { it.name == themeName } ?: run {
                    LOG.warn("Theme not found for variant=$variant islandsUi=$islandsUi name='$themeName'")
                    return
                }
            // Second arg is `lockEditorScheme = false` so the same-named editor
            // scheme follows the theme switch instead of locking on the old.
            lafManager.setCurrentLookAndFeel(laf, false)
            lafManager.updateUI()
        } catch (exception: RuntimeException) {
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
