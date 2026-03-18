package dev.ayuislands.settings

import dev.ayuislands.settings.AyuIslandsState.Companion.DEFAULT_AUTO_FIT_MAX_WIDTH
import dev.ayuislands.settings.AyuIslandsState.Companion.DEFAULT_FIXED_WIDTH
import dev.ayuislands.settings.AyuIslandsState.Companion.DEFAULT_GIT_AUTO_FIT_MIN_WIDTH

/**
 * Pure state machine for tool window width mode tracking.
 * Extracted from WorkspacePanel for testability — no Swing dependencies.
 */
class PanelWidthState {
    var pendingMode = PanelWidthMode.DEFAULT
    var storedMode = PanelWidthMode.DEFAULT
    var pendingAutoFitMaxWidth = DEFAULT_AUTO_FIT_MAX_WIDTH
    var storedAutoFitMaxWidth = DEFAULT_AUTO_FIT_MAX_WIDTH
    var pendingAutoFitMinWidth = DEFAULT_GIT_AUTO_FIT_MIN_WIDTH
    var storedAutoFitMinWidth = DEFAULT_GIT_AUTO_FIT_MIN_WIDTH
    var pendingFixedWidth = DEFAULT_FIXED_WIDTH
    var storedFixedWidth = DEFAULT_FIXED_WIDTH

    fun load(
        mode: PanelWidthMode,
        autoFitMaxWidth: Int,
        fixedWidth: Int,
        autoFitMinWidth: Int = DEFAULT_GIT_AUTO_FIT_MIN_WIDTH,
    ) {
        storedMode = mode
        pendingMode = mode
        storedAutoFitMaxWidth = autoFitMaxWidth
        pendingAutoFitMaxWidth = autoFitMaxWidth
        storedAutoFitMinWidth = autoFitMinWidth
        pendingAutoFitMinWidth = autoFitMinWidth
        storedFixedWidth = fixedWidth
        pendingFixedWidth = fixedWidth
    }

    fun isModified(): Boolean =
        pendingMode != storedMode ||
            pendingAutoFitMaxWidth != storedAutoFitMaxWidth ||
            pendingAutoFitMinWidth != storedAutoFitMinWidth ||
            pendingFixedWidth != storedFixedWidth

    fun commitStored() {
        storedMode = pendingMode
        storedAutoFitMaxWidth = pendingAutoFitMaxWidth
        storedAutoFitMinWidth = pendingAutoFitMinWidth
        storedFixedWidth = pendingFixedWidth
    }

    fun reset() {
        pendingMode = storedMode
        pendingAutoFitMaxWidth = storedAutoFitMaxWidth
        pendingAutoFitMinWidth = storedAutoFitMinWidth
        pendingFixedWidth = storedFixedWidth
    }

    companion object {
        fun widthSummary(state: PanelWidthState): String =
            when (state.pendingMode) {
                PanelWidthMode.DEFAULT -> "Default"
                PanelWidthMode.AUTO_FIT -> {
                    val minPart =
                        if (state.pendingAutoFitMinWidth != DEFAULT_GIT_AUTO_FIT_MIN_WIDTH) {
                            "min ${state.pendingAutoFitMinWidth} \u00B7 "
                        } else {
                            ""
                        }
                    "Auto-fit \u00B7 ${minPart}max ${state.pendingAutoFitMaxWidth}px"
                }
                PanelWidthMode.FIXED -> "Fixed \u00B7 ${state.pendingFixedWidth}px"
            }
    }
}
