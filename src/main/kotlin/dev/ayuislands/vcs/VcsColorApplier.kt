package dev.ayuislands.vcs

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.util.concurrency.annotations.RequiresEdt
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.theme.AyuEditorSchemeScope
import dev.ayuislands.theme.EditorSchemeOverrides
import dev.ayuislands.theme.EditorSchemeOwner
import org.jetbrains.annotations.TestOnly
import java.awt.Color
import java.awt.Window

/**
 * Applier — writes blended VCS colors into the live [EditorColorsScheme]
 * based on the current [VcsColorContext] snapshot (or the persisted
 * [AyuIslandsState] when no snapshot is active).
 *
 * Dispatches per [VcsWriteMode]:
 *  - [VcsWriteMode.COLOR_KEY] — `scheme.setColor(ColorKey.find(name), tinted)`
 *  - [VcsWriteMode.TEXT_ATTR_BG] — read existing attributes, clone with the
 *    blended background, preserve foreground / effect / error stripe / font
 *    type, write back via `scheme.setAttributes`.
 *
 * Writes are restricted to the exact Ayu scheme matching the active variant.
 * Disabling restores each value captured before the first write, unless an
 * external editor or plugin changed that entry while Ayu owned it.
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
        if (!LicenseChecker.isLicensedOrGrace()) {
            revertAll()
            return
        }
        val state = AyuIslandsSettings.getInstance().state
        val variant = AyuVariant.detect()
        if (variant == null) {
            LOG.debug("VcsColorApplier.applyAll: no Ayu variant active; skipping")
            return
        }
        ApplicationManager.getApplication().invokeLater {
            val currentVariant = AyuVariant.detect() ?: return@invokeLater
            if (VcsColorContext.isEnabled(state)) {
                val scheme = AyuEditorSchemeScope.activeScheme() ?: return@invokeLater
                writeAll(scheme, state, currentVariant)
                repaintAllWindows()
            } else {
                restoreWithRetry(rearm = true)
            }
        }
    }

    /** Apply pending Settings values to the exact scheme selected by the user. */
    @RequiresEdt
    fun applyCurrentScheme() {
        val state = AyuIslandsSettings.getInstance().state
        if (!VcsColorContext.isEnabled(state)) {
            restoreWithRetry(rearm = true)
            return
        }
        if (!LicenseChecker.isLicensedOrGrace()) {
            revertAll()
            return
        }
        val variant = AyuVariant.detect() ?: return
        val scheme = AyuEditorSchemeScope.activeScheme() ?: return

        writeAll(scheme, state, variant)
        repaintAllWindows()
    }

    /**
     * Restores every VCS entry still owned by Ayu. Exact scheme identity and
     * pre-write values are retained by the shared editor-scheme ledger.
     */
    fun revertAll() {
        ApplicationManager.getApplication().invokeLater {
            restoreWithRetry()
        }
    }

    private fun restoreWithRetry(rearm: Boolean = false) {
        var failure: RuntimeException? = null
        try {
            AyuEditorSchemeScope.restore(EditorSchemeOwner.Vcs)
        } catch (firstFailure: RuntimeException) {
            failure = firstFailure
            try {
                AyuEditorSchemeScope.restore(EditorSchemeOwner.Vcs)
                failure = null
            } catch (retryFailure: RuntimeException) {
                failure = retryFailure
            }
        }
        if (failure != null) LOG.warn("VcsColorApplier: VCS scheme restore failed after retry", failure)
        if (rearm) {
            EditorSchemeOverrides.rearm(
                EditorSchemeOwner.Vcs,
                EditorColorsManager.getInstance().allSchemes.asIterable(),
            )
        }
        repaintAllWindows()
    }

    @TestOnly
    fun resetClaims() {
        AyuEditorSchemeScope.resetClaims()
    }

    private fun writeAll(
        scheme: EditorColorsScheme,
        state: AyuIslandsState,
        variant: AyuVariant,
    ) {
        val failed = writeEveryEntry(scheme, state, variant)
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
                if (!safeWriteEntry(scheme, entry, blendFor(entry, variant, intensity))) {
                    failed++
                }
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
            VcsWriteMode.COLOR_KEY ->
                AyuEditorSchemeScope.writeColor(scheme, EditorSchemeOwner.Vcs, ColorKey.find(entry.keyName), tinted)
            VcsWriteMode.TEXT_ATTR_BG -> writeTextAttrBackground(scheme, entry.keyName, tinted)
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
        AyuEditorSchemeScope.writeAttributes(scheme, EditorSchemeOwner.Vcs, key, updated)
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
