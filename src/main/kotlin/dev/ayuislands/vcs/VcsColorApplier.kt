package dev.ayuislands.vcs

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import java.awt.Color
import java.awt.Window

/**
 * Phase 40.2 applier — writes blended VCS colors into the live
 * [EditorColorsScheme] based on the current [VcsColorContext] snapshot (or
 * persisted [AyuIslandsState] when no snapshot is active).
 *
 * Wave 2 + 2.5 scope: dispatches per [VcsWriteMode]:
 *  - [VcsWriteMode.COLOR_KEY] — `scheme.setColor(ColorKey.find(name), tinted)`
 *  - [VcsWriteMode.TEXT_ATTR_BG] — read existing attributes, clone with the
 *    blended background, preserve foreground / effect / error stripe / font
 *    type, write back via `scheme.setAttributes`.
 *
 * When master is OFF or the snapshot says "disabled", the applier issues
 * null-writes so the scheme falls back to the stock XML values — no leftover
 * tinted state when a user disables the feature.
 */
internal object VcsColorApplier {
    private val LOG = logger<VcsColorApplier>()

    /**
     * Apply VCS colors for the currently active variant.
     *
     * Reads the [AyuIslandsState] singleton, resolves the active [AyuVariant],
     * and writes a blended color (or null = stock revert) for every known
     * palette entry. After writing, repaints all visible Windows so the
     * gutter / Project View / diff viewer markers reflect the new palette
     * without requiring a theme reload.
     *
     * Safe to call from any thread — the EDT hop happens inside this method.
     */
    fun applyAll() {
        val state = AyuIslandsSettings.getInstance().state
        val variant = AyuVariant.detect()
        if (variant == null) {
            LOG.debug("VcsColorApplier.applyAll: no Ayu variant active; skipping")
            return
        }
        ApplicationManager.getApplication().invokeLater {
            writeAll(state, variant)
            repaintAllWindows()
        }
    }

    /**
     * Reverts every VCS color entry to stock — null-writes via
     * `scheme.setColor` (for ColorKey entries) and `scheme.setAttributes`
     * (for TextAttributesKey entries) so the scheme falls back to the XML
     * baseline. Used when the master kill-switch flips ON → OFF.
     */
    fun revertAll() {
        ApplicationManager.getApplication().invokeLater {
            val scheme = EditorColorsManager.getInstance().globalScheme
            val failed = revertEveryEntry(scheme)
            if (failed > 0) LOG.warn("VcsColorApplier.revertAll: $failed entries failed to revert; see prior warnings")
            repaintAllWindows()
        }
    }

    private fun writeAll(
        state: AyuIslandsState,
        variant: AyuVariant,
    ) {
        val scheme = EditorColorsManager.getInstance().globalScheme
        val failed =
            if (VcsColorContext.isEnabled(state)) {
                writeEveryEntry(scheme, state, variant)
            } else {
                revertEveryEntry(scheme)
            }
        if (failed > 0) LOG.warn("VcsColorApplier.writeAll: $failed entries failed; see prior warnings")
    }

    private fun writeEveryEntry(
        scheme: EditorColorsScheme,
        state: AyuIslandsState,
        variant: AyuVariant,
    ): Int {
        var failed = 0
        for ((category, entries) in VcsColorPalette.allCategoriesAndEntries()) {
            val intensity = VcsColorContext.currentIntensity(category, state)
            for (entry in entries) {
                if (!safeWriteEntry(scheme, entry, blendFor(entry, variant, intensity))) failed++
            }
        }
        return failed
    }

    private fun revertEveryEntry(scheme: EditorColorsScheme): Int {
        var failed = 0
        for ((_, entries) in VcsColorPalette.allCategoriesAndEntries()) {
            for (entry in entries) {
                if (!safeRevertEntry(scheme, entry)) failed++
            }
        }
        return failed
    }

    private fun safeWriteEntry(
        scheme: EditorColorsScheme,
        entry: VcsPaletteEntry,
        tinted: Color,
    ): Boolean =
        try {
            writeEntry(scheme, entry, tinted)
            true
        } catch (exception: RuntimeException) {
            LOG.warn("VcsColorApplier: writing ${entry.keyName} (${entry.mode}) failed", exception)
            false
        }

    private fun safeRevertEntry(
        scheme: EditorColorsScheme,
        entry: VcsPaletteEntry,
    ): Boolean =
        try {
            revertEntry(scheme, entry)
            true
        } catch (exception: RuntimeException) {
            LOG.warn("VcsColorApplier: reverting ${entry.keyName} (${entry.mode}) failed", exception)
            false
        }

    private fun blendFor(
        entry: VcsPaletteEntry,
        variant: AyuVariant,
        intensity: VcsIntensity,
    ): Color {
        val (base, target) = VcsColorPalette.endpoints(entry, variant)
        return VcsColorBlender.blend(base, target, intensity)
    }

    private fun writeEntry(
        scheme: EditorColorsScheme,
        entry: VcsPaletteEntry,
        tinted: Color,
    ) {
        when (entry.mode) {
            VcsWriteMode.COLOR_KEY -> scheme.setColor(ColorKey.find(entry.keyName), tinted)
            VcsWriteMode.TEXT_ATTR_BG -> writeTextAttrBackground(scheme, entry.keyName, tinted)
        }
    }

    private fun revertEntry(
        scheme: EditorColorsScheme,
        entry: VcsPaletteEntry,
    ) {
        when (entry.mode) {
            VcsWriteMode.COLOR_KEY -> scheme.setColor(ColorKey.find(entry.keyName), null)
            VcsWriteMode.TEXT_ATTR_BG -> scheme.setAttributes(TextAttributesKey.find(entry.keyName), null)
        }
    }

    /**
     * Writes [background] into the BACKGROUND slot of [TextAttributesKey] named
     * [keyName], preserving every other TextAttributes field (foreground, effect
     * color/type, error stripe color, font type). Without the clone-preserve
     * dance, our background write would clobber the existing error stripe color
     * and any future foreground accent.
     */
    private fun writeTextAttrBackground(
        scheme: EditorColorsScheme,
        keyName: String,
        background: Color,
    ) {
        val key = TextAttributesKey.find(keyName)
        val existing = scheme.getAttributes(key)
        val updated =
            TextAttributes(
                existing?.foregroundColor,
                background,
                existing?.effectColor,
                existing?.effectType,
                existing?.fontType ?: 0,
            )
        updated.errorStripeColor = existing?.errorStripeColor
        scheme.setAttributes(key, updated)
    }

    /**
     * Repaints every visible top-level [Window] so the scheme writes propagate
     * without waiting for the next focus event. Mirrors the chrome applier's
     * post-apply repaint discipline.
     */
    private fun repaintAllWindows() {
        for (window in Window.getWindows()) {
            try {
                if (window.isShowing) window.repaint()
            } catch (exception: RuntimeException) {
                LOG.debug("VcsColorApplier.repaintAllWindows: window repaint failed", exception)
            }
        }
    }
}
