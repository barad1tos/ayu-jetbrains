package dev.ayuislands.accent.elements

import com.intellij.openapi.diagnostic.logger
import dev.ayuislands.accent.AccentElement
import dev.ayuislands.accent.AccentElementId
import dev.ayuislands.accent.ChromeBaseColors
import dev.ayuislands.accent.ChromeTintBlender
import dev.ayuislands.accent.LiveChromeRefresher
import dev.ayuislands.settings.AyuIslandsSettings
import java.awt.Color
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.UIManager

/**
 * Tints panel / tool-window borders per CHROME-05.
 *
 * NB: `OnePixelDivider.background` is deliberately excluded — it is already
 * managed by [dev.ayuislands.accent.AccentApplicator]'s `ALWAYS_ON_UI_KEYS`
 * list. Double-writing from two sources would make revert ambiguous (whose
 * null wins?), so this element owns only the tool-window-level border keys.
 *
 * Intensity is read directly from [AyuIslandsSettings.state] per CONTEXT D-03.
 */
class PanelBorderElement : AccentElement {
    override val id = AccentElementId.PANEL_BORDER
    override val displayName = "Panel borders"

    internal val uiKeys: List<String> =
        listOf(
            "ToolWindow.Header.borderColor",
            "ToolWindow.borderColor",
        )

    override fun apply(color: Color) {
        val intensity = AyuIslandsSettings.getInstance().state.effectiveChromeTintIntensity()
        var tintedHeaderBorder: Color? = null
        val keysSeen = mutableListOf<String>()
        val keysMissed = mutableListOf<String>()
        for (key in uiKeys) {
            val baseColor = ChromeBaseColors.get(key)
            if (baseColor == null) {
                keysMissed.add(key)
                continue
            }
            keysSeen.add(key)
            val tinted = ChromeTintBlender.blend(color, baseColor, intensity)
            UIManager.put(key, tinted)
            if (key == HEADER_BORDER_KEY) tintedHeaderBorder = tinted
        }
        // Phase 40.2 M-3: first-apply INFO diagnostic so a future platform FQN
        // rename surfaces in idea.log. Includes the UIManager key matches/misses
        // AND the peer-walk target classes we'll ask LiveChromeRefresher to
        // find. Gated on a one-shot AtomicBoolean so repeated apply passes stay
        // quiet.
        if (firstApplyLogged.compareAndSet(false, true)) {
            LOG.info(
                "PanelBorderElement first apply: keysSeen=$keysSeen keysMissed=$keysMissed " +
                    "walkTargets=[divider=$DIVIDER_PEER_CLASS,ancestor=$TOOL_WINDOW_ANCESTOR_CLASS]",
            )
        }
        // Level 2 Gap-4: push tinted tool-window header border to the live OnePixelDivider
        // peer. The divider caches its color at construction and re-renders on repaint;
        // UIManager writes alone don't refresh it (see VERIFICATION Gap 4, CHROME-05).
        //
        // Round 2 review A-1 (CRITICAL correctness): `OnePixelDivider` is used IDE-wide —
        // editor splitters, Project/editor divider, Settings dialog splitters, diff gutter,
        // Run/Debug splitter, file-chooser splitter. A blind `refreshByClassName` walk
        // would tint every divider in every window, not just tool-window header borders.
        // Constrain the walk to dividers whose ancestor chain contains the tool-window
        // decorator (`com.intellij.toolWindow.InternalDecoratorImpl`, javap-verified against
        // `intellij.platform.ide.impl.jar` in 2026.1 — it extends `InternalDecorator`
        // which extends `JBPanel`, so `Container.getParent()` chain traversal is safe).
        tintedHeaderBorder?.let {
            LiveChromeRefresher.refreshByClassNameInsideAncestorClass(
                DIVIDER_PEER_CLASS,
                TOOL_WINDOW_ANCESTOR_CLASS,
                it,
            )
        }
    }

    override fun revert() {
        for (key in uiKeys) {
            UIManager.put(key, null)
        }
        // D-14 symmetry: hand the divider peer back to LAF default.
        LiveChromeRefresher.clearByClassNameInsideAncestorClass(
            DIVIDER_PEER_CLASS,
            TOOL_WINDOW_ANCESTOR_CLASS,
        )
    }

    private companion object {
        private val LOG = logger<PanelBorderElement>()

        /**
         * One-shot gate for the per-session first-apply diagnostic log (Phase 40.2 M-3).
         * Logs which UIManager keys resolved vs missed and which peer classes the
         * refresher will walk for — so a future platform FQN rename surfaces in
         * idea.log without needing a debugger attach.
         */
        private val firstApplyLogged = AtomicBoolean(false)

        const val HEADER_BORDER_KEY = "ToolWindow.Header.borderColor"

        /**
         * FQN of the internal 1-pixel divider used for tool-window panel borders — package-private,
         * so runtime class-name string match is the supported lookup (see 40-12 §B).
         */
        const val DIVIDER_PEER_CLASS = "com.intellij.openapi.ui.OnePixelDivider"

        /**
         * FQN of the tool-window decorator that wraps every tool-window content panel (and
         * therefore its header border dividers). Verified via `javap` against
         * `/Applications/IntelliJ IDEA.app/Contents/lib/intellij.platform.ide.impl.jar` in
         * 2026.1 — `com.intellij.toolWindow.InternalDecoratorImpl` extends
         * `com.intellij.openapi.wm.impl.InternalDecorator` which extends `JBPanel`, so
         * the ancestor check walks a plain AWT `Container.getParent()` chain.
         */
        const val TOOL_WINDOW_ANCESTOR_CLASS = "com.intellij.toolWindow.InternalDecoratorImpl"
    }
}
