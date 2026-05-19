package dev.ayuislands.accent.toolbar

import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AccentResolver
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.toolbar.popup.Density
import dev.ayuislands.accent.toolbar.popup.ToggleTile
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import java.awt.Container
import java.awt.GridLayout
import java.nio.file.Files
import java.nio.file.Paths
import javax.swing.JLabel
import javax.swing.JPanel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Related-toggles section coverage:
 *   - layout is a 2 × 2 [GridLayout] of [ToggleTile] composites,
 *   - tile labels in fixed top-to-bottom / left-to-right order,
 *   - Chrome tinting binds to `chromeStatusBar` ONLY (`chromeMainToolbar` /
 *     `chromeToolWindowStripe` / `chromeNavBar` / `chromePanelBorder` are
 *     forbidden in this file),
 *   - single-source-of-truth `bindSelected` plumbing preserved.
 */
class QuickSwitcherRelatedTogglesSectionTest {
    @BeforeTest
    fun setUp() {
        val state = AyuIslandsState()
        val settings = mockk<AyuIslandsSettings>(relaxed = true)
        every { settings.state } returns state
        mockkObject(AyuIslandsSettings.Companion)
        every { AyuIslandsSettings.getInstance() } returns settings
        mockkObject(AccentApplicator)
        every { AccentApplicator.resolveFocusedProject() } returns null
        mockkObject(AccentResolver)
        every { AccentResolver.resolve(any(), any()) } returns "#FFB454"
        mockkObject(AyuVariant.Companion)
        every { AyuVariant.detect() } returns AyuVariant.DARK
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `layout is 2x2 GridLayout`() {
        val section = QuickSwitcherRelatedTogglesSection()
        val layout = (section.component as JPanel).layout as GridLayout
        assertEquals(2, layout.rows)
        assertEquals(2, layout.columns)
    }

    @Test
    fun `grid contains exactly 4 ToggleTile children`() {
        val section = QuickSwitcherRelatedTogglesSection()
        val tiles = (section.component as JPanel).components.filterIsInstance<ToggleTile>()
        assertEquals(4, tiles.size, "Expected exactly 4 ToggleTile children")
    }

    @Test
    fun `tile labels in fixed order Chrome tinting Glow Accent rotation Follow system`() {
        val section = QuickSwitcherRelatedTogglesSection()
        val labels = collectLabelTexts(section.component as Container)
        val expected = listOf("Chrome tinting", "Glow", "Accent rotation", "Follow system accent")
        assertTrue(labels.containsAll(expected), "Tile labels drifted; got=$labels expected to include $expected")
        // Preserve the visual tile order.
        assertEquals(
            expected,
            labels.filter { it in expected },
            "Tile order drifted",
        )
    }

    @Test
    fun `Chrome tinting binds to chromeStatusBar only`() {
        val source = Files.readString(Paths.get(SOURCE_PATH))
        val statusBarRefs = "chromeStatusBar".toRegex().findAll(source).count()
        assertTrue(statusBarRefs >= 2, "Expected ≥2 chromeStatusBar refs (getter + setter), got $statusBarRefs")
        for (forbidden in FORBIDDEN_SURFACES) {
            val count = forbidden.toRegex().findAll(source).count()
            assertEquals(0, count, "Toggles section must NOT reference $forbidden (count=$count)")
        }
    }

    @Test
    fun `bindSelected reads back the matching state field at construction time`() {
        val state = AyuIslandsSettings.getInstance().state
        state.chromeStatusBar = false
        state.glowEnabled = true
        state.accentRotationEnabled = false
        state.followSystemAccent = true

        val section = QuickSwitcherRelatedTogglesSection()
        val tiles = (section.component as JPanel).components.filterIsInstance<ToggleTile>()
        val byLabel = tiles.associateBy { label -> findLabelText(label) }
        assertEquals(false, byLabel["Chrome tinting"]?.toggleSwitch?.isSelected)
        assertEquals(true, byLabel["Glow"]?.toggleSwitch?.isSelected)
        assertEquals(false, byLabel["Accent rotation"]?.toggleSwitch?.isSelected)
        assertEquals(true, byLabel["Follow system accent"]?.toggleSwitch?.isSelected)
    }

    @Test
    fun `source carries at least four bindSelected calls (Pattern Q regression lock)`() {
        val source = Files.readString(Paths.get(SOURCE_PATH))
        val count = "bindSelected".toRegex().findAll(source).count()
        assertTrue(count >= 4, "Expected ≥4 bindSelected calls, got $count")
    }

    @Test
    fun `source has zero AccentApplicator calls (Pattern G adjacency lock)`() {
        val source = Files.readString(Paths.get(SOURCE_PATH))
        val applyHex = "applyFromHexString".toRegex().findAll(source).count()
        assertEquals(0, applyHex, "Must NOT call applyFromHexString from the toggles section (Pattern G)")
    }

    @Test
    fun `external binding write does not loop through switch flip (re-entry guard)`() {
        // Regression lock — pattern that previously broke: the per-tile
        // listener writes `binding.isSelected = newValue` which fires
        // `ItemEvent` on the binding; the binding's ItemListener catches it and
        // calls `switch.flip()`; `flip()` writes back to binding → ping-pong.
        // The equality guard (`if (switch.isSelected != binding.isSelected)`)
        // saves the case where both already agree, but a future refactor that
        // breaks the equality check leaves the loop open. The lexical
        // `suppressEvents` guard makes the no-loop invariant unconditional.
        //
        // Simulate an external write: flip the binding directly (as a Settings
        // page edit would do via bindSelected's setter), then assert that the
        // switch follows once — NOT twice (which would indicate ping-pong).
        val section = QuickSwitcherRelatedTogglesSection()
        val tiles = (section.component as JPanel).components.filterIsInstance<ToggleTile>()
        val chromeTile = tiles.first { tile -> labelOf(tile) == "Chrome tinting" }
        val switch = chromeTile.toggleSwitch
        val startState = switch.isSelected

        // Reach the hidden binding via reflection on the section's
        // persistenceRoot — its first child is the chrome binding.
        val persistenceRootField =
            QuickSwitcherRelatedTogglesSection::class.java.getDeclaredField("persistenceRoot")
        persistenceRootField.isAccessible = true
        val persistenceRoot =
            persistenceRootField.get(section) as com.intellij.openapi.ui.DialogPanel
        val chromeBinding =
            collectCheckBoxes(persistenceRoot).first { box ->
                box.text.contains("Chrome tinting")
            }

        // External edit: write directly to the binding. The switch must follow
        // once. If suppressEvents is broken, this would either:
        //   (a) infinite-loop and StackOverflow during dispatch, or
        //   (b) toggle the switch back to startState (binding listener flips
        //       switch, switch's listener flips binding back, equality check
        //       sees match and stops — net result is no change).
        chromeBinding.isSelected = !startState

        // Switch must mirror the new binding state — exactly one flip.
        assertEquals(
            !startState,
            switch.isSelected,
            "Switch must mirror external binding write; ping-pong would reset to startState=$startState",
        )
        // Binding state must equal switch state — no drift.
        assertEquals(switch.isSelected, chromeBinding.isSelected, "Binding and switch must agree post-write")
    }

    @Test
    fun `source no longer exposes a public fun apply (dead code removed)`() {
        // The `fun apply()` exposed on the section was never called by
        // QuickSwitcherPopup (per-tile-click persistence is the whole
        // contract). Lock its absence so a future "completeness" PR does
        // not re-add a misleading dead method.
        val source = Files.readString(Paths.get(SOURCE_PATH))
        // Only allow the private `persistenceRoot.apply()` calls inside the
        // listener body; no top-level `fun apply()` definition on the class.
        val publicApplyDefs = "\\s{4}fun apply\\(\\)".toRegex().findAll(source).count()
        assertEquals(0, publicApplyDefs, "Public fun apply() must be deleted; persistence is per-tile-click")
    }

    @Test
    fun `density grid gap matches Density TILE_GAP`() {
        val section = QuickSwitcherRelatedTogglesSection()
        val layout = (section.component as JPanel).layout as GridLayout
        val expected =
            com.intellij.util.ui.JBUI
                .scale(Density.TILE_GAP)
        assertEquals(expected, layout.hgap)
        assertEquals(expected, layout.vgap)
    }

    private fun labelOf(tile: ToggleTile): String = findLabelText(tile)

    private fun collectCheckBoxes(
        root: Container,
        out: MutableList<javax.swing.JCheckBox> = mutableListOf(),
    ): List<javax.swing.JCheckBox> {
        for (child in root.components) {
            if (child is javax.swing.JCheckBox) out.add(child)
            if (child is Container) collectCheckBoxes(child, out)
        }
        return out
    }

    private fun findLabelText(tile: ToggleTile): String {
        val labels = collectLabelTexts(tile)
        // Labels include any leading icon JLabel (text empty) — keep the non-empty.
        return labels.firstOrNull { it.isNotEmpty() } ?: ""
    }

    private fun collectLabelTexts(
        root: Container,
        out: MutableList<String> = mutableListOf(),
    ): List<String> {
        for (child in root.components) {
            if (child is JLabel) {
                val text = child.text
                if (!text.isNullOrEmpty()) out.add(text)
            }
            if (child is Container) collectLabelTexts(child, out)
        }
        return out
    }

    private companion object {
        const val SOURCE_PATH = "src/main/kotlin/dev/ayuislands/accent/toolbar/QuickSwitcherRelatedTogglesSection.kt"
        val FORBIDDEN_SURFACES =
            listOf(
                "chromeMainToolbar",
                "chromeToolWindowStripe",
                "chromeNavBar",
                "chromePanelBorder",
            )
    }
}
