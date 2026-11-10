package dev.ayuislands.studio.session

import com.intellij.openapi.editor.colors.EditorColorsManager
import dev.ayuislands.AyuLaf
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.glow.GlowOverlayManager
import dev.ayuislands.licensing.LicenseEntitlement
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioProcess
import javax.swing.SwingUtilities
import javax.swing.UIManager

/** Reads platform incarnations directly; callbacks supply facts from their existing owners. */
internal class StudioNativeHost(
    private val focus: () -> StudioNativeFocus,
    private val entitlement: () -> LicenseEntitlement,
    private val accent: () -> StudioAccentResult?,
    private val managers: () -> List<GlowOverlayManager>,
    private val process: StudioProcess?,
) {
    fun observe(): StudioNativeObservation {
        checkEdt()
        // First service access can initialize the LAF; capture identities only after those reads settle.
        val colors = EditorColorsManager.getInstance()
        val variant = AyuVariant.fromThemeName(AyuLaf.currentThemeName())
        return StudioNativeObservation(
            UIManager.getLookAndFeel(),
            colors.globalScheme,
            focus(),
            entitlement(),
            variant,
            accent(),
            StudioList(managers()),
            process,
        )
    }

    /** Recovery reads no forward Accent input and cannot reacquire lost native handles. */
    fun recoveryFacts(): StudioNativeFacts {
        checkEdt()
        val confirmed = entitlement()
        check(confirmed != LicenseEntitlement.UNKNOWN) { "Native entitlement is unconfirmed" }
        return StudioNativeFacts(confirmed == LicenseEntitlement.LICENSED, process)
    }

    private fun checkEdt() {
        check(SwingUtilities.isEventDispatchThread()) { "Studio native observations require EDT" }
    }
}
