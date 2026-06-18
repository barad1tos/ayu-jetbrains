package dev.ayuislands.accent.elements

import com.intellij.openapi.diagnostic.logger
import dev.ayuislands.accent.AccentElementId
import dev.ayuislands.accent.ChromeTarget
import dev.ayuislands.accent.WcagForeground
import java.awt.Color
import java.util.concurrent.atomic.AtomicBoolean

private val panelBorderFirstApplyLogged = AtomicBoolean(false)

/**
 * Tints panel / tool-window borders. This element has no foreground —
 * [foregroundKeys] is intentionally empty.
 *
 * NB: `OnePixelDivider.background` is deliberately excluded from UIManager
 * writes. `OnePixelDivider` is shared across editor splitters, tool-window
 * dividers, Settings, and diff views, so blind UIManager accent writes leak
 * into unrelated surfaces. This element owns only the tool-window-level border
 * keys and refreshes live dividers through the ancestor-scoped peer target.
 *
 * Intensity is read directly from [dev.ayuislands.settings.AyuIslandsSettings.state].
 *
 * Ancestor-scoped walk: `OnePixelDivider` is used IDE-wide — editor splitters,
 * Project/editor divider, Settings dialog splitters, diff gutter, Run/Debug
 * splitter, file-chooser splitter. A blind class-name walk would tint every
 * divider in every window, not just tool-window header borders. The
 * [ChromeTarget.ByClassNameInside] variant constrains the walk to dividers
 * whose ancestor chain contains the tool-window decorator
 * (`com.intellij.toolWindow.InternalDecoratorImpl`, javap-verified against
 * `intellij.platform.ide.impl.jar` in 2026.1).
 */
class PanelBorderElement : AbstractChromeElement() {
    override val id = AccentElementId.PANEL_BORDER
    override val displayName = "Panel borders"

    /** Exposed `internal` so PanelBorderElementTest can lock the ALWAYS_ON_UI_KEYS non-overlap invariant. */
    internal val uiKeys: List<String> =
        listOf(
            "ToolWindow.Header.borderColor",
            "ToolWindow.borderColor",
        )

    override val backgroundKeys: List<String> get() = uiKeys

    /** No foregrounds — panel borders paint only their tinted background. */
    override val foregroundKeys: List<String> = emptyList()

    /** Unused (foregroundKeys is empty) but required by the base — declared for completeness. */
    override val foregroundTextTarget: WcagForeground.TextTarget = WcagForeground.TextTarget.PRIMARY_TEXT

    override val peerTarget: ChromeTarget =
        ChromeTarget.ByClassNameInside(
            target = classFqn(DIVIDER_PEER_CLASS),
            ancestor = classFqn(TOOL_WINDOW_ANCESTOR_CLASS),
        )

    override fun onBackgroundsTinted(tintedBackgrounds: Map<String, Color>) {
        // First-apply INFO diagnostic so a future platform FQN rename surfaces
        // in idea.log. Includes the UIManager key matches/misses AND the
        // peer-walk target classes we'll ask LiveChromeRefresher to find.
        // Gated on a one-shot AtomicBoolean so repeated apply passes stay
        // quiet.
        if (panelBorderFirstApplyLogged.compareAndSet(false, true)) {
            val keysSeen = tintedBackgrounds.keys.toList()
            val keysMissed = backgroundKeys.filter { it !in tintedBackgrounds }
            LOG.info(
                "PanelBorderElement first apply: keysSeen=$keysSeen keysMissed=$keysMissed " +
                    "walkTargets=[divider=$DIVIDER_PEER_CLASS,ancestor=$TOOL_WINDOW_ANCESTOR_CLASS]",
            )
        }
    }

    private companion object {
        private val LOG = logger<PanelBorderElement>()

        /**
         * FQN of the internal 1-pixel divider used for tool-window panel
         * borders — the type is package-private, so runtime class-name string
         * match is the supported lookup pattern.
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
