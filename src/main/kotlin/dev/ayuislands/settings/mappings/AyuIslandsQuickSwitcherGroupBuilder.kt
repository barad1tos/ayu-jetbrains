package dev.ayuislands.settings.mappings

import com.intellij.ui.dsl.builder.Panel
import dev.ayuislands.settings.AyuIslandsSettings

/**
 * Standalone builder for the "Quick Switcher" group in Settings → Ayu Islands → Accent.
 * Mirrors [OverridesGroupBuilder]'s shape: `buildGroup` / `isModified` / `apply` / `reset`.
 *
 * Lives in its own file (D-17 extraction) to keep
 * [dev.ayuislands.settings.AyuIslandsAccentPanel] under detekt's `LargeClass` threshold —
 * the panel is already large (708 LOC at Wave 5 plan time); inlining the group body here
 * would risk breaching the cap as more Quick Switcher rows land in future micro-plans.
 *
 * The single row in this group exposes the master toggle for the Quick Switcher widget's
 * visibility in the main toolbar. The corresponding state field
 * [dev.ayuislands.settings.AyuIslandsState.quickSwitcherWidgetEnabled] (default ON) is
 * polled by the chip's `update()` on every BGT tick (~500 ms cadence), so flipping this
 * checkbox takes visible effect without any explicit re-apply call from here. Pattern G
 * adjacency: this builder owns the state write; the cascade owns the propagation.
 */
class AyuIslandsQuickSwitcherGroupBuilder {
    private var storedEnabled: Boolean = true
    private var pendingEnabled: Boolean = true

    fun buildGroup(panel: Panel) {
        val settings = AyuIslandsSettings.getInstance()
        storedEnabled = settings.state.quickSwitcherWidgetEnabled
        pendingEnabled = storedEnabled

        panel.collapsibleGroup("Quick Switcher") {
            row {
                checkBox("Show quick-switcher widget in main toolbar")
                    .applyToComponent { isSelected = pendingEnabled }
                    .onChanged { pendingEnabled = it.isSelected }
                    .comment(
                        "The chip lives in the main toolbar (right side) when an Ayu " +
                            "variant is active. Click to switch variant or accent; right-" +
                            "click for quick actions (premium).",
                    )
            }
        }
    }

    fun isModified(): Boolean = pendingEnabled != storedEnabled

    fun apply() {
        AyuIslandsSettings.getInstance().state.quickSwitcherWidgetEnabled = pendingEnabled
        storedEnabled = pendingEnabled
        // NO explicit re-apply needed — the chip's `update()` polls
        // `state.quickSwitcherWidgetEnabled` on every BGT tick (~500 ms cadence), so the
        // chip visibility flips on its own.
    }

    fun reset() {
        pendingEnabled = storedEnabled
    }
}
