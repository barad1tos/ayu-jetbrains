package dev.ayuislands.accent

/** Explicit Chrome choices captured from one Studio candidate before native writes. */
internal data class ChromeTintSnapshot(
    val chromeStatusBar: Boolean,
    val chromeMainToolbar: Boolean,
    val chromeToolWindowStripe: Boolean,
    val chromeNavBar: Boolean,
    val chromePanelBorder: Boolean,
    val intensity: TintIntensity,
    val chromeTintingEnabled: Boolean = true,
) {
    fun isToggleEnabled(id: AccentElementId): Boolean {
        require(id.group == AccentGroup.CHROME) { "Not a Chrome element: $id" }
        return chromeTintingEnabled &&
            when (id) {
                AccentElementId.STATUS_BAR -> chromeStatusBar
                AccentElementId.MAIN_TOOLBAR -> chromeMainToolbar
                AccentElementId.TOOL_WINDOW_STRIPE -> chromeToolWindowStripe
                AccentElementId.NAV_BAR -> chromeNavBar
                AccentElementId.PANEL_BORDER -> chromePanelBorder
                else -> error("Unmapped Chrome element: $id")
            }
    }
}
