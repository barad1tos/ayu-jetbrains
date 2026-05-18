package dev.ayuislands.accent.toolbar

import com.intellij.openapi.diagnostic.logger
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import dev.ayuislands.settings.AyuIslandsSettings
import javax.swing.JComponent

/**
 * Premium-block sub-section: four checkboxes that mirror the matching Settings
 * panel fields directly via `AyuIslandsSettings.state` property delegation.
 * Per D-13, the popup's toggle and the Settings panel's toggle drive the SAME
 * field — single source of truth, no separate persistence path.
 *
 * Each `bindSelected({ state.X }, { state.X = it })` callback runs on EDT
 * (Kotlin UI DSL contract); the existing `LafManagerListener` cascade and the
 * accent-applicator-driven state-observation path pick the change up within
 * one EDT cycle. Do NOT call the applicator's hex-string apply entry-point
 * from inside the `bindSelected` callback — the cascade already runs
 * (Pattern G adjacency; the parity test source-greps for the forbidden token).
 *
 * Field-mapping note for the "Chrome tinting" toggle:
 *   `AyuIslandsState` does NOT carry a single `chromeTintEnabled` Boolean (the
 *   plan-time assumption was wrong — verified by grep). Chrome tinting is
 *   controlled in Settings via five per-surface `chrome*` Booleans plus an
 *   `Int` intensity. The popup's compact "Chrome tinting" affordance binds to
 *   `chromeStatusBar` — the most user-visible chrome surface (the status bar
 *   is always rendered) — and trades the five-surface granularity for a
 *   single-click on/off. Power users still get the full per-surface controls
 *   in Settings → Ayu Islands → Chrome Tinting.
 */
internal class QuickSwitcherRelatedTogglesSection {
    val component: JComponent

    init {
        val state = AyuIslandsSettings.getInstance().state
        component =
            panel {
                row {
                    checkBox("Chrome tinting")
                        .bindSelected({ state.chromeStatusBar }, { state.chromeStatusBar = it })
                }
                row {
                    checkBox("Glow")
                        .bindSelected({ state.glowEnabled }, { state.glowEnabled = it })
                }
                row {
                    checkBox("Accent rotation")
                        .bindSelected({ state.accentRotationEnabled }, { state.accentRotationEnabled = it })
                }
                row {
                    checkBox("Follow system accent")
                        .bindSelected({ state.followSystemAccent }, { state.followSystemAccent = it })
                }
            }
    }

    private companion object {
        val LOG = logger<QuickSwitcherRelatedTogglesSection>()
    }
}
