package dev.ayuislands.accent.toolbar

import com.intellij.icons.AllIcons
import com.intellij.openapi.diagnostic.logger
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AccentDefaults
import dev.ayuislands.accent.AccentResolver
import dev.ayuislands.accent.AyuVariant
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
 * Wave-7 redesign of the toggles section per 48-REDESIGN-SPEC §3.5: 2-column ×
 * 2-row grid of [ToggleTile] composites (icon + label + [ToggleSwitch]) — replaces
 * the Wave-4 vertical [JCheckBox] stack.
 *
 * Per Locked Answer #3, "Chrome tinting" still binds to `state.chromeStatusBar`
 * ONLY (the most user-visible chrome surface); the full 5-surface granularity
 * stays in Settings. Other tiles bind to `glowEnabled`, `accentRotationEnabled`,
 * `followSystemAccent` respectively.
 *
 * D-13 single-source-of-truth: each tile owns a hidden [JCheckBox] driven by the
 * Kotlin UI DSL `bindSelected({ state.X }, { state.X = it })` — the visual is a
 * custom switch but the binding sink stays the DSL-managed checkbox, so the
 * existing persistence path keeps working. The hidden checkbox lives inside a
 * tiny [com.intellij.openapi.ui.DialogPanel] held by [persistenceRoot] so
 * `apply()` flushes the pending writes back into `AyuIslandsSettings.state`.
 *
 * Pattern A — every paint mutation calls `repaint()` on the calling thread
 * (already EDT for mouse events).
 */
internal class QuickSwitcherRelatedTogglesSection {
    val component: JComponent
    private val persistenceRoot: com.intellij.openapi.ui.DialogPanel

    init {
        val state = AyuIslandsSettings.getInstance().state
        val accentSupplier: () -> String = {
            val variant = AyuVariant.detect() ?: AyuVariant.DARK
            try {
                AccentResolver.resolve(AccentApplicator.resolveFocusedProject(), variant)
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

    /**
     * Flushes pending [bindSelected] writes back into `AyuIslandsSettings.state`.
     * Exposed so callers (popup container) can trigger the apply on popup-close.
     * The DSL contract requires `apply()` to commit pending writes; tile clicks
     * mutate the hidden checkbox's `isSelected`, then this call propagates them.
     */
    fun apply() {
        persistenceRoot.apply()
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
                    binding.isSelected = newValue
                    persistenceRoot.apply()
                },
            )
        // Mirror reverse: if the hidden checkbox flips externally (Settings page
        // edit), the ToggleSwitch follows.
        binding.addItemListener { _ ->
            if (switch.isSelected != binding.isSelected) {
                switch.flip()
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
