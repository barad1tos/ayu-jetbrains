package dev.ayuislands.accent

import dev.ayuislands.settings.AyuIslandsState

/**
 * Immutable chrome settings captured from the Settings panel's pending values.
 *
 * The chrome settings UI intentionally commits state only after the re-apply
 * succeeds. Chrome elements still need the pending values during that apply,
 * so settings exposes this snapshot through [ChromeTintContext] while the
 * existing applicator path runs instead of committing global state early.
 */
internal data class ChromeTintSnapshot(
    val chromeStatusBar: Boolean,
    val chromeMainToolbar: Boolean,
    val chromeToolWindowStripe: Boolean,
    val chromeNavBar: Boolean,
    val chromePanelBorder: Boolean,
    val intensity: TintIntensity,
) {
    fun isToggleEnabled(
        id: AccentElementId,
        fallbackState: AyuIslandsState,
    ): Boolean =
        when (id) {
            AccentElementId.STATUS_BAR -> chromeStatusBar
            AccentElementId.MAIN_TOOLBAR -> chromeMainToolbar
            AccentElementId.TOOL_WINDOW_STRIPE -> chromeToolWindowStripe
            AccentElementId.NAV_BAR -> chromeNavBar
            AccentElementId.PANEL_BORDER -> chromePanelBorder
            else -> fallbackState.isToggleEnabled(id)
        }
}

internal object ChromeTintContext {
    private val current = ThreadLocal<ChromeTintSnapshot?>()

    fun currentIntensity(fallbackState: AyuIslandsState): TintIntensity =
        current.get()?.intensity ?: fallbackState.effectiveChromeTintIntensity()

    fun isToggleEnabled(
        fallbackState: AyuIslandsState,
        id: AccentElementId,
    ): Boolean = current.get()?.isToggleEnabled(id, fallbackState) ?: fallbackState.isToggleEnabled(id)

    fun <T> withSnapshot(
        snapshot: ChromeTintSnapshot?,
        block: () -> T,
    ): T {
        if (snapshot == null) return block()
        val previous = current.get()
        current.set(snapshot)
        return try {
            block()
        } finally {
            if (previous == null) {
                current.remove()
            } else {
                current.set(previous)
            }
        }
    }
}
