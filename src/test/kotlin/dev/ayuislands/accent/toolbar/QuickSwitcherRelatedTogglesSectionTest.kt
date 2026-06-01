package dev.ayuislands.accent.toolbar

import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AccentContext
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
import io.mockk.verify
import java.awt.Container
import java.awt.GridLayout
import java.awt.image.BufferedImage
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
 *   - Chrome tinting writes `chromeStatusBar` only,
 *   - hidden binding writes mirror the visible switch without re-entry loops.
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
        every { AccentResolver.resolve(any(), any<AccentContext>()) } returns "#FFB454"
        every { AccentResolver.resolve(any(), any<AyuVariant>()) } returns "#FFB454"
        mockkObject(AyuVariant.Companion)
        every { AyuVariant.detect() } returns AyuVariant.DARK
        mockkObject(AccentContext.Companion)
        every { AccentContext.detect() } returns AccentContext.Ayu(AyuVariant.DARK)
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
    fun `clicking Chrome tinting updates status bar without touching other chrome surfaces`() {
        val state = AyuIslandsSettings.getInstance().state
        state.chromeStatusBar = false
        state.chromeMainToolbar = true
        state.chromeToolWindowStripe = true
        state.chromeNavBar = false
        state.chromePanelBorder = true

        val section = QuickSwitcherRelatedTogglesSection()
        val chromeTile =
            (section.component as JPanel)
                .components
                .filterIsInstance<ToggleTile>()
                .first { tile -> labelOf(tile) == "Chrome tinting" }

        chromeTile.toggleSwitch.flip()

        assertEquals(true, state.chromeStatusBar, "Chrome tile must toggle the visible status-bar chrome surface")
        assertEquals(true, state.chromeMainToolbar, "Chrome tile must not touch main-toolbar chrome")
        assertEquals(true, state.chromeToolWindowStripe, "Chrome tile must not touch tool-window stripe chrome")
        assertEquals(false, state.chromeNavBar, "Chrome tile must not touch nav-bar chrome")
        assertEquals(true, state.chromePanelBorder, "Chrome tile must not touch panel-border chrome")
        verify(exactly = 0) { AccentApplicator.applyFromHexString(any()) }
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
    fun `density grid gap matches Density TILE_GAP`() {
        val section = QuickSwitcherRelatedTogglesSection()
        val layout = (section.component as JPanel).layout as GridLayout
        val expected =
            com.intellij.util.ui.JBUI
                .scale(Density.TILE_GAP)
        assertEquals(expected, layout.hgap)
        assertEquals(expected, layout.vgap)
    }

    @Test
    fun `selected toggle paint resolves accent through external context`() {
        val state = AyuIslandsSettings.getInstance().state
        state.chromeStatusBar = true
        every { AccentContext.detect() } returns AccentContext.External
        every { AccentResolver.resolve(any(), AccentContext.External) } returns "#5CCFE6"

        val section = QuickSwitcherRelatedTogglesSection()
        val chromeTile =
            (section.component as JPanel)
                .components
                .filterIsInstance<ToggleTile>()
                .first { tile -> labelOf(tile) == "Chrome tinting" }

        chromeTile.toggleSwitch.setSize(32, 16)
        val image = BufferedImage(32, 16, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            chromeTile.toggleSwitch.paintForTest(graphics)
        } finally {
            graphics.dispose()
        }

        verify(exactly = 1) { AccentResolver.resolve(any(), AccentContext.External) }
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
        return labels.firstOrNull { it.isNotEmpty() }.orEmpty()
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
}
