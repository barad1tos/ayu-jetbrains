package dev.ayuislands.accent.toolbar

import com.intellij.icons.AllIcons
import com.intellij.openapi.diagnostic.logger
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AccentContext
import dev.ayuislands.accent.AccentDefaults
import dev.ayuislands.accent.AccentResolver
import dev.ayuislands.accent.toolbar.popup.Density
import dev.ayuislands.accent.toolbar.popup.ToggleSwitch
import dev.ayuislands.accent.toolbar.popup.ToggleTile
import dev.ayuislands.settings.AyuIslandsSettings
import java.awt.GridLayout
import javax.swing.Icon
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * 2-column × 2-row grid of [ToggleTile] composites (icon + label +
 * [ToggleSwitch]) — replaces an earlier vertical [JCheckBox] stack.
 *
 * "Chrome tinting" binds to `state.chromeStatusBar` ONLY (the most user-visible
 * chrome surface); the full 5-surface granularity stays in Settings. Other
 * tiles bind to `glowEnabled`, `accentRotationEnabled`, `followSystemAccent`
 * respectively.
 *
 * Single-source-of-truth: each tile owns a hidden [JCheckBox] driven by the
 * Kotlin UI DSL `bindSelected({ state.X }, { state.X = it })` — the visual is a
 * custom switch but the binding sink stays the DSL-managed checkbox, so the
 * existing persistence path keeps working. The hidden checkbox lives inside a
 * tiny [com.intellij.openapi.ui.DialogPanel] held by [persistenceRoot].
 *
 * **Persistence model.** Each tile click is its own commit: the user-facing
 * [ToggleSwitch] flip writes back to the hidden binding checkbox and then calls
 * `persistenceRoot.apply()` synchronously — the popup container does NOT need
 * to run any close-time flush. No external `fun apply()` is exposed because no
 * caller needs one; per-tile-click persistence is the whole contract.
 *
 * **Re-entry guard.** A lexical `suppressEvents` flag protects the bi-directional
 * binding ↔ switch link from a `flip()` → `isSelected = ...` → `ItemEvent` →
 * `flip()` ping-pong if an external Settings-page edit fires the binding's
 * `ItemEvent` while the popup is open. The equality guard (`if (switch.isSelected
 * != binding.isSelected) flip()`) by itself is fragile — `suppressEvents` makes
 * the no-loop invariant load-bearing on the lexical scope, not on equality.
 *
 * Pattern A — every paint mutation calls `repaint()` on the calling thread
 * (already EDT for mouse events).
 */
internal class QuickSwitcherRelatedTogglesSection {
    val component: JComponent
    private val persistenceRoot: com.intellij.openapi.ui.DialogPanel

    /**
     * Re-entry guard around the bi-directional binding ↔ switch link. Set to
     * `true` for the lexical scope of any write that would otherwise re-trigger
     * the sibling listener (switch → binding, or binding → switch). The
     * sibling listener checks this flag first and short-circuits when set,
     * preventing the ItemEvent ping-pong cycle.
     */
    private var suppressEvents: Boolean = false

    init {
        val state = AyuIslandsSettings.getInstance().state
        val accentSupplier: () -> String = {
            val context = AccentContext.detectQuickSwitcher()
            try {
                if (context == null) {
                    AccentDefaults.MIRAGE_HEX
                } else {
                    AccentResolver.resolve(AccentApplicator.resolveFocusedProject(), context)
                }
            } catch (exception: RuntimeException) {
                LOG.warn("Toggles section accent resolve failed", exception)
                AccentDefaults.MIRAGE_HEX
            }
        }

        val chromeBinding: JCheckBox
        val glowBinding: JCheckBox
        val rotationBinding: JCheckBox
        val followBinding: JCheckBox

        // Build all four hidden DSL checkboxes inside ONE persistence panel so a
        // single `apply()` flushes the pending writes. The panel is NOT added to
        // the visible component tree — it is held by reference so the binding
        // sinks stay alive.
        var chromeRef: JCheckBox? = null
        var glowRef: JCheckBox? = null
        var rotationRef: JCheckBox? = null
        var followRef: JCheckBox? = null
        persistenceRoot =
            panel {
                row {
                    chromeRef =
                        checkBox("Chrome tinting")
                            .bindSelected({ state.chromeStatusBar }, { state.chromeStatusBar = it })
                            .component
                }
                row {
                    glowRef =
                        checkBox("Glow")
                            .bindSelected({ state.glowEnabled }, { state.glowEnabled = it })
                            .component
                }
                row {
                    rotationRef =
                        checkBox("Accent rotation")
                            .bindSelected({ state.accentRotationEnabled }, { state.accentRotationEnabled = it })
                            .component
                }
                row {
                    followRef =
                        checkBox("Follow system accent")
                            .bindSelected({ state.followSystemAccent }, { state.followSystemAccent = it })
                            .component
                }
            }
        chromeBinding = checkNotNull(chromeRef)
        glowBinding = checkNotNull(glowRef)
        rotationBinding = checkNotNull(rotationRef)
        followBinding = checkNotNull(followRef)

        val chromeTile = buildTile(AllIcons.General.Layout, "Chrome tinting", chromeBinding, accentSupplier)
        val glowTile = buildTile(AllIcons.General.Note, "Glow", glowBinding, accentSupplier)
        val rotationTile = buildTile(AllIcons.Actions.Refresh, "Accent rotation", rotationBinding, accentSupplier)
        val followTile = buildTile(AllIcons.General.Settings, "Follow system accent", followBinding, accentSupplier)

        component =
            JPanel(
                GridLayout(
                    GRID_ROWS,
                    GRID_COLS,
                    JBUI.scale(Density.TILE_GAP),
                    JBUI.scale(Density.TILE_GAP),
                ),
            ).apply {
                isOpaque = false
                add(chromeTile)
                add(glowTile)
                add(rotationTile)
                add(followTile)
            }
    }

    private fun buildTile(
        icon: Icon,
        label: String,
        binding: JCheckBox,
        accentSupplier: () -> String,
    ): ToggleTile {
        val switch =
            ToggleSwitch(
                initialSelected = binding.isSelected,
                accentSupplier = accentSupplier,
                listener = { newValue ->
                    // suppressEvents wraps the binding write so the binding's
                    // ItemListener (below) does NOT re-fire switch.flip() in
                    // response — which would loop through this listener again.
                    suppressEvents = true
                    try {
                        binding.isSelected = newValue
                        persistenceRoot.apply()
                    } finally {
                        suppressEvents = false
                    }
                },
            )
        // Mirror reverse: if the hidden checkbox flips externally (Settings page
        // edit fired through the same state), the ToggleSwitch follows. The
        // suppressEvents guard short-circuits the loopback when the switch's
        // own listener (above) wrote the binding.
        binding.addItemListener { _ ->
            if (suppressEvents) return@addItemListener
            if (switch.isSelected != binding.isSelected) {
                suppressEvents = true
                try {
                    switch.flip()
                } finally {
                    suppressEvents = false
                }
            }
        }
        return ToggleTile(icon, label, switch)
    }

    private companion object {
        const val GRID_ROWS = 2
        const val GRID_COLS = 2
        val LOG = logger<QuickSwitcherRelatedTogglesSection>()
    }
}
