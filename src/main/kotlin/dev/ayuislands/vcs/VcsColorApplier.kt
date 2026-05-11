package dev.ayuislands.vcs

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.openapi.editor.colors.EditorColorsManager
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import java.awt.Color
import java.awt.Window

/**
 * Phase 40.2 applier — writes blended VCS colors into the live
 * [com.intellij.openapi.editor.colors.EditorColorsScheme] based on the
 * current [VcsColorContext] snapshot (or persisted [AyuIslandsState] when no
 * snapshot is active).
 *
 * Wave 2 ColorKey scope (no TextAttributesKey writes yet — that's Wave 2.x):
 *  - For every (category, keyName) in [VcsColorPalette.KEYS_BY_CATEGORY], the
 *    applier reads the per-category slider via [VcsColorContext.currentIntensity],
 *    blends the palette's Whisper / Cyberpunk endpoints with [VcsColorBlender],
 *    and writes the result via `scheme.setColor(ColorKey.find(name), ...)`.
 *  - When master is OFF or the snapshot says "disabled", the applier issues
 *    null-writes (`setColor(key, null)`) so the scheme falls back to the stock
 *    XML values — no leftover tinted state when a user disables the feature.
 *
 * Lives as an [object] mirroring [dev.ayuislands.accent.ChromeTintBlender] —
 * the applier holds no mutable state; the [VcsColorContext] ThreadLocal is the
 * only ephemeral channel, and the persisted [AyuIslandsState] is the durable one.
 */
internal object VcsColorApplier {
    private val LOG = logger<VcsColorApplier>()

    /**
     * Apply VCS colors for the currently active variant.
     *
     * Reads the [AyuIslandsState] singleton, resolves the active [AyuVariant],
     * and writes a blended color (or null = stock revert) for every known
     * ColorKey in [VcsColorPalette]. After writing, repaints all visible
     * Windows so the gutter / Project View / scrollbar markers reflect the
     * new palette without requiring a theme reload.
     *
     * Safe to call from any thread — the EDT hop happens inside this method
     * when needed. Callers do not need to wrap in [ApplicationManager.getApplication]
     * `invokeLater`.
     */
    fun applyAll() {
        val state = AyuIslandsSettings.getInstance().state
        val variant = AyuVariant.detect()
        if (variant == null) {
            LOG.info("VcsColorApplier.applyAll: no Ayu variant active; skipping")
            return
        }
        ApplicationManager.getApplication().invokeLater {
            writeAll(state, variant)
            repaintAllWindows()
        }
    }

    /**
     * Reverts every VCS ColorKey to stock — equivalent to writing `null` for
     * each key so the scheme falls back to the XML baseline. Used when the
     * master kill-switch flips from ON to OFF.
     */
    fun revertAll() {
        ApplicationManager.getApplication().invokeLater {
            val scheme = EditorColorsManager.getInstance().globalScheme
            for ((_, keys) in VcsColorPalette.KEYS_BY_CATEGORY) {
                for (keyName in keys) {
                    scheme.setColor(ColorKey.find(keyName), null)
                }
            }
            repaintAllWindows()
        }
    }

    private fun writeAll(
        state: AyuIslandsState,
        variant: AyuVariant,
    ) {
        if (!VcsColorContext.isEnabled(state)) {
            // Disabled — write nulls so the scheme falls back to XML stock.
            val scheme = EditorColorsManager.getInstance().globalScheme
            for ((_, keys) in VcsColorPalette.KEYS_BY_CATEGORY) {
                for (keyName in keys) {
                    scheme.setColor(ColorKey.find(keyName), null)
                }
            }
            return
        }
        val scheme = EditorColorsManager.getInstance().globalScheme
        for ((category, keys) in VcsColorPalette.KEYS_BY_CATEGORY) {
            val intensity = VcsColorContext.currentIntensity(category, state)
            for (keyName in keys) {
                val tinted = blendFor(keyName, variant, intensity)
                scheme.setColor(ColorKey.find(keyName), tinted)
            }
        }
    }

    private fun blendFor(
        keyName: String,
        variant: AyuVariant,
        intensity: VcsIntensity,
    ): Color {
        val (base, target) = VcsColorPalette.endpoints(keyName, variant)
        return VcsColorBlender.blend(base, target, intensity)
    }

    /**
     * Repaints every visible top-level [Window] so the scheme writes propagate
     * without waiting for the next focus event. Mirrors the chrome applier's
     * post-apply repaint discipline — without it, the gutter and Project View
     * keep their cached tinted color until the user clicks somewhere.
     */
    private fun repaintAllWindows() {
        for (window in Window.getWindows()) {
            if (window.isShowing) {
                window.repaint()
            }
        }
    }
}
